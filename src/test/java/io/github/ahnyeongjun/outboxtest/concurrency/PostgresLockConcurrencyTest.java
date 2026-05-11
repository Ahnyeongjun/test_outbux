package io.github.ahnyeongjun.outboxtest.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ahnyeongjun.outbox.adapter.jdbc.JdbcOutboxStore;
import io.github.ahnyeongjun.outbox.adapter.jdbc.PostgreSQLDialect;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * 실제 PostgreSQL 위에서 락/동시성 동작 검증.
 *
 * <p>Docker 없이 Zonky embedded-postgres 가 PG 바이너리를 받아 로컬에서 띄운다 (첫 실행 ~50MB).
 *
 * <p>검증 시나리오:
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED} 가 다중 워커에서 row 를 중복 분배하지 않는가
 *   <li>동시 markSent 가 잘 직렬화되는가
 *   <li>비즈니스 트랜잭션이 롤백되면 outbox INSERT 도 같이 롤백되는가 (원자성)
 *   <li>고부하 동시 INSERT × 동시 픽업 × cleanup 교차 시 deadlock 없는가
 * </ul>
 */
class PostgresLockConcurrencyTest {

    private static EmbeddedPostgres pg;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcOutboxStore store;
    private static TransactionTemplate tx;

    @BeforeAll
    static void startPostgres() throws IOException {
        pg = EmbeddedPostgres.builder().start();
        dataSource = pg.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcOutboxStore(
                new NamedParameterJdbcTemplate(dataSource),
                new PostgreSQLDialect());
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        jdbc.execute("""
                CREATE SEQUENCE IF NOT EXISTS outbox_seq_seq START 1;
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS outbox (
                    id         BIGSERIAL   PRIMARY KEY,
                    seq        BIGINT      NOT NULL DEFAULT NEXTVAL('outbox_seq_seq'),
                    domain     VARCHAR(50) NOT NULL,
                    event_type VARCHAR(20) NOT NULL,
                    source     VARCHAR(20) NOT NULL,
                    payload    JSONB,
                    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
                    sent_at    TIMESTAMP
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_outbox_status_seq ON outbox (status, seq ASC)");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (pg != null) pg.close();
    }

    @BeforeEach
    void cleanTable() {
        jdbc.execute("TRUNCATE TABLE outbox RESTART IDENTITY");
        jdbc.execute("ALTER SEQUENCE outbox_seq_seq RESTART WITH 1");
    }

    private void insertPending(int count) {
        List<Outbox> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            events.add(Outbox.builder()
                    .domain("USER").eventType("CREATED").source("INTERNAL")
                    .payload("{\"i\":" + i + "}").build());
        }
        store.saveAll(events);
    }

    // -------------------- 1. SKIP LOCKED 중복 분배 방지 --------------------

    @Test
    @DisplayName("4개 워커 × FOR UPDATE SKIP LOCKED → 같은 row 가 두 워커에 분배되지 않음")
    void skipLocked_neverDistributesSameRowToMultipleWorkers() throws Exception {
        int totalRows = 200;
        int workers = 4;
        int batchSize = 30;
        insertPending(totalRows);

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<List<Long>>> futures = new ArrayList<>();

        try {
            for (int w = 0; w < workers; w++) {
                futures.add(pool.submit(() -> {
                    List<Long> picked = new ArrayList<>();
                    while (true) {
                        // OutboxScheduler.processOutbox 와 동일한 패턴 — pick + markSent 를 같은 tx 에 묶어
                        // 락이 유지되는 동안 SENT 마킹 완료. 별도 tx 로 쪼개면 락이 풀려 race 발생.
                        List<Outbox> batch = tx.execute(s -> {
                            List<Outbox> got = store.findPendingWithLock(batchSize);
                            if (!got.isEmpty()) store.markSent(got);
                            return got;
                        });
                        if (batch.isEmpty()) break;
                        batch.forEach(o -> picked.add(o.getId()));
                    }
                    return picked;
                }));
            }

            Set<Long> all = new HashSet<>();
            int collected = 0;
            for (Future<List<Long>> f : futures) {
                List<Long> ids = f.get(60, TimeUnit.SECONDS);
                collected += ids.size();
                for (Long id : ids) {
                    assertThat(all.add(id))
                            .as("워커 간 row id %d 중복 분배 — SKIP LOCKED 위반", id)
                            .isTrue();
                }
            }

            assertThat(collected).as("모든 row 가 정확히 한 번씩 처리").isEqualTo(totalRows);
            assertThat(store.countPending()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------- 2. 동시 INSERT × 동시 픽업 × markSent 데드락 부재 --------------------

    @Test
    @DisplayName("동시 INSERT + 동시 픽업 + 동시 markSent 가 교차해도 deadlock 없이 완료")
    void noDeadlock_underHighConcurrentInsertPickupMarkSent() throws Exception {
        int writers = 4;
        int pickers = 2;
        int writesPerWriter = 100;
        int batchSize = 25;

        ExecutorService pool = Executors.newFixedThreadPool(writers + pickers);
        AtomicInteger consumed = new AtomicInteger();

        try {
            // 픽업 워커: 폴링하며 보이는 모든 PENDING 처리. writes 끝나면 자연 종료.
            List<Future<?>> pickerFutures = new ArrayList<>();
            for (int p = 0; p < pickers; p++) {
                pickerFutures.add(pool.submit(() -> {
                    long emptyRoundsRemaining = 5;
                    while (emptyRoundsRemaining > 0) {
                        // pick + markSent 같은 tx (락 유지)
                        List<Outbox> batch = tx.execute(s -> {
                            List<Outbox> got = store.findPendingWithLock(batchSize);
                            if (!got.isEmpty()) store.markSent(got);
                            return got;
                        });
                        if (batch.isEmpty()) {
                            emptyRoundsRemaining--;
                            try { Thread.sleep(30); } catch (InterruptedException e) { return null; }
                            continue;
                        }
                        emptyRoundsRemaining = 5;
                        consumed.addAndGet(batch.size());
                    }
                    return null;
                }));
            }

            // 쓰기 워커: 한 트랜잭션당 1건 INSERT (실 비즈니스 시뮬)
            List<Future<?>> writerFutures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                final int wid = w;
                writerFutures.add(pool.submit(() -> {
                    for (int i = 0; i < writesPerWriter; i++) {
                        final int idx = i;
                        tx.execute(new TransactionCallbackWithoutResult() {
                            @Override
                            protected void doInTransactionWithoutResult(TransactionStatus status) {
                                store.saveAll(List.of(Outbox.builder()
                                        .domain("USER").eventType("CREATED").source("INTERNAL")
                                        .payload("{\"w\":" + wid + ",\"i\":" + idx + "}").build()));
                            }
                        });
                    }
                    return null;
                }));
            }

            for (Future<?> f : writerFutures) f.get(60, TimeUnit.SECONDS);
            for (Future<?> f : pickerFutures) f.get(60, TimeUnit.SECONDS);

            int totalWritten = writers * writesPerWriter;
            assertThat(store.countPending()).isZero();
            long sent = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox WHERE status = 'SENT'", Long.class);
            assertThat(sent).isEqualTo(totalWritten);
            assertThat(consumed.get()).isEqualTo(totalWritten);
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------- 3. 트랜잭션 원자성 — 비즈니스 롤백 시 outbox 도 롤백 --------------------

    @Test
    @DisplayName("saveAll 이 비즈니스 tx 롤백에 함께 묶임 (REQUIRED 전파)")
    void saveAll_rollsBackWithOuterTransaction() {
        long before = store.countPending();

        // 외부 tx 내에서 saveAll 호출 후 강제 롤백
        tx.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                store.saveAll(List.of(
                        Outbox.builder().domain("USER").eventType("CREATED").source("INTERNAL")
                                .payload("{\"x\":1}").build()
                ));
                status.setRollbackOnly();
            }
        });

        assertThat(store.countPending())
                .as("외부 tx 가 롤백되면 saveAll 결과도 함께 사라져야 함 (REQUIRED 전파)")
                .isEqualTo(before);
    }

    // -------------------- 4. cleanup 과 동시 픽업 — markSent 후 즉시 deleteOldSent 도 안전 --------------------

    @Test
    @DisplayName("markSent 직후 deleteOldSent 가 동시에 돌아도 race 없이 안전")
    void cleanUpAndMarkSent_doNotRaceOrDeadlock() throws Exception {
        insertPending(100);

        // 먼저 절반을 SENT 로 표시하고 sent_at 을 8 일 전으로 만들어 cleanup 대상으로 만듦
        tx.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                List<Outbox> half = store.findPendingWithLock(50);
                store.markSent(half);
            }
        });
        jdbc.update("UPDATE outbox SET sent_at = NOW() - INTERVAL '8 days' WHERE status = 'SENT'");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 동시에 cleanup × pickup 진행
            Future<?> cleanup = pool.submit(() -> {
                tx.execute(new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(TransactionStatus status) {
                        store.deleteOldSent();
                    }
                });
                return null;
            });
            Future<?> pickup = pool.submit(() -> {
                tx.execute(new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(TransactionStatus status) {
                        List<Outbox> rest = store.findPendingWithLock(100);
                        store.markSent(rest);
                    }
                });
                return null;
            });

            cleanup.get(30, TimeUnit.SECONDS);
            pickup.get(30, TimeUnit.SECONDS);

            long total = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class);
            // 50건 cleanup, 50건 SENT 남음
            assertThat(total).isEqualTo(50);
            assertThat(store.countPending()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}

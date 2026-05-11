package io.github.ahnyeongjun.outboxtest.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
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

import io.github.ahnyeongjun.outbox.store.JdbcOutboxStore;
import io.github.ahnyeongjun.outbox.store.PostgreSQLDialect;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * 실제 PostgreSQL 위에서 락/동시성 동작 검증 — 공개 SPI({@code processBatch}) 만으로 진행.
 *
 * <p>저수준 락 메서드는 SPI 에서 제거됐으므로, 동시성 테스트도 사용자가 실제로 호출할 수 있는
 * 인터페이스를 통해 검증한다. 이는 동시에 "{@code processBatch} 안에 락 의미론이 제대로 캡슐화돼
 * 있는지" 까지 함께 검증하는 효과가 있다.
 *
 * <p>Docker 없이 Zonky embedded-postgres 가 PG 바이너리를 받아 로컬에서 띄운다.
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
        DataSourceTransactionManager txm = new DataSourceTransactionManager(dataSource);
        store = new JdbcOutboxStore(
                new NamedParameterJdbcTemplate(dataSource),
                new PostgreSQLDialect(),
                txm);
        tx = new TransactionTemplate(txm);

        jdbc.execute("CREATE SEQUENCE IF NOT EXISTS outbox_seq_seq START 1");
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

    // -------------------- 1. processBatch 동시 호출 중복 분배 방지 --------------------

    @Test
    @DisplayName("4개 워커가 동시에 processBatch 호출 → 같은 row 가 두 워커에 분배되지 않음")
    void processBatch_concurrent_neverDistributesSameRowTwice() throws Exception {
        int totalRows = 200;
        int workers = 4;
        int batchSize = 30;
        insertPending(totalRows);

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        ConcurrentLinkedQueue<Long> pickedAcrossWorkers = new ConcurrentLinkedQueue<>();
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int w = 0; w < workers; w++) {
                futures.add(pool.submit(() -> {
                    int total = 0;
                    while (true) {
                        int handled = store.processBatch(batchSize, batch -> {
                            for (Outbox o : batch) pickedAcrossWorkers.add(o.getId());
                        });
                        if (handled == 0) break;
                        total += handled;
                    }
                    return total;
                }));
            }

            int collected = 0;
            for (Future<Integer> f : futures) collected += f.get(60, TimeUnit.SECONDS);

            assertThat(collected).as("모든 row 가 정확히 한 번씩 처리").isEqualTo(totalRows);

            Set<Long> deduped = new HashSet<>(pickedAcrossWorkers);
            assertThat(deduped).as("워커 간 row 중복 없음").hasSize(totalRows);
            assertThat(store.countPending()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------- 2. 동시 saveAll × 동시 processBatch deadlock 부재 --------------------

    @Test
    @DisplayName("동시 saveAll + 동시 processBatch 가 교차해도 deadlock 없이 완료")
    void noDeadlock_underHighConcurrentSaveAndProcess() throws Exception {
        int writers = 4;
        int pickers = 2;
        int writesPerWriter = 100;
        int batchSize = 25;

        ExecutorService pool = Executors.newFixedThreadPool(writers + pickers);
        AtomicInteger consumed = new AtomicInteger();

        try {
            // 픽업 워커
            List<Future<?>> pickerFutures = new ArrayList<>();
            for (int p = 0; p < pickers; p++) {
                pickerFutures.add(pool.submit(() -> {
                    int emptyRoundsRemaining = 5;
                    while (emptyRoundsRemaining > 0) {
                        int handled = store.processBatch(batchSize, batch -> {
                            // 핸들러 본문 — 실 운영에선 파일 쓰기/외부 전송 등
                        });
                        if (handled == 0) {
                            emptyRoundsRemaining--;
                            try { Thread.sleep(30); } catch (InterruptedException e) { return null; }
                            continue;
                        }
                        emptyRoundsRemaining = 5;
                        consumed.addAndGet(handled);
                    }
                    return null;
                }));
            }

            // 쓰기 워커 — 한 트랜잭션당 1건 INSERT
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

        assertThat(store.countPending()).isEqualTo(before);
    }

    // -------------------- 4. 핸들러 예외 → FAILED 마킹 + 후속 픽업에서 제외 --------------------

    @Test
    @DisplayName("processBatch 핸들러 예외 시 배치 전체가 FAILED 로 마킹되어 다음 픽업에서 제외")
    void processBatch_handlerException_marksFailedAndExcludesFromNextPickup() {
        insertPending(5);

        int handled = store.processBatch(10, batch -> {
            throw new RuntimeException("simulated handler failure");
        });
        assertThat(handled).isEqualTo(5);

        Long failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status='FAILED'", Long.class);
        assertThat(failed).isEqualTo(5);

        // 두 번째 픽업 — FAILED 는 PENDING 이 아니므로 빈 배치
        int next = store.processBatch(10, batch -> {});
        assertThat(next).isZero();
    }

    // -------------------- 5. cleanup 과 동시 픽업 안전성 --------------------

    @Test
    @DisplayName("deleteOldSent 가 동시 진행되는 processBatch 와 race 없이 안전")
    void cleanUpAndProcessBatch_doNotRace() throws Exception {
        insertPending(100);

        // 절반 미리 SENT 처리 후 sent_at 을 8일 전으로 → cleanup 대상화
        store.processBatch(50, batch -> {});
        jdbc.update("UPDATE outbox SET sent_at = NOW() - INTERVAL '8 days' WHERE status = 'SENT'");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cleanup = pool.submit(() -> { store.deleteOldSent(); return null; });
            Future<?> pickup = pool.submit(() -> store.processBatch(100, batch -> {}));

            cleanup.get(30, TimeUnit.SECONDS);
            pickup.get(30, TimeUnit.SECONDS);

            long total = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class);
            assertThat(total).isEqualTo(50);  // 50건 cleanup, 50건 SENT 남음
            assertThat(store.countPending()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}

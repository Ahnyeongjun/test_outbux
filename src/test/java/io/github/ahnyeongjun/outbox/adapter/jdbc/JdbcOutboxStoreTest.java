package io.github.ahnyeongjun.outbox.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import io.github.ahnyeongjun.outbox.model.Outbox;

/**
 * H2 임베디드 DB 위에서 H2 호환 방언으로 JdbcOutboxStore 동작을 검증.
 * 실 PostgreSQL/MySQL 방언은 H2 와 호환되지 않으므로 별도 H2Dialect 를 정의해 사용한다.
 */
class JdbcOutboxStoreTest {

    private EmbeddedDatabase db;
    private JdbcOutboxStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(db);

        jdbc.execute("""
                CREATE TABLE outbox (
                    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
                    seq         BIGINT,
                    domain      VARCHAR(50) NOT NULL,
                    event_type  VARCHAR(20) NOT NULL,
                    source      VARCHAR(20) NOT NULL,
                    payload     CLOB,
                    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    sent_at     TIMESTAMP
                )""");

        store = new JdbcOutboxStore(
                new NamedParameterJdbcTemplate((DataSource) db),
                new H2TestDialect());
    }

    @Test
    void saveAll_insertsMultipleRowsAsPending() {
        store.saveAll(List.of(
                Outbox.builder().domain("USER").eventType("CREATED").source("INTERNAL").payload("{\"a\":1}").build(),
                Outbox.builder().domain("USER").eventType("UPDATED").source("INTERNAL").payload("{\"b\":2}").build()
        ));

        assertThat(store.countPending()).isEqualTo(2);
    }

    @Test
    void findPendingWithLock_returnsLimitedRowsOrderedById() {
        store.saveAll(List.of(
                Outbox.builder().domain("USER").eventType("CREATED").source("INTERNAL").payload("{}").build(),
                Outbox.builder().domain("USER").eventType("UPDATED").source("INTERNAL").payload("{}").build(),
                Outbox.builder().domain("USER").eventType("DELETED").source("INTERNAL").payload("{}").build()
        ));

        List<Outbox> picked = store.findPendingWithLock(2);

        assertThat(picked).hasSize(2);
        assertThat(picked.get(0).getEventType()).isEqualTo("CREATED");
        assertThat(picked.get(1).getEventType()).isEqualTo("UPDATED");
    }

    @Test
    void markSent_updatesStatusAndSentAt() {
        store.saveAll(List.of(
                Outbox.builder().domain("USER").eventType("CREATED").source("INTERNAL").payload("{}").build()
        ));
        List<Outbox> picked = store.findPendingWithLock(10);

        store.markSent(picked);

        assertThat(store.countPending()).isZero();
        Long sentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status = 'SENT' AND sent_at IS NOT NULL", Long.class);
        assertThat(sentCount).isEqualTo(1);
    }

    @Test
    void markFailed_movesRowToFailedStatus() {
        store.saveAll(List.of(
                Outbox.builder().domain("USER").eventType("CREATED").source("INTERNAL").payload("{}").build()
        ));
        Long id = jdbc.queryForObject("SELECT MIN(id) FROM outbox", Long.class);

        store.markFailed(id);

        String status = jdbc.queryForObject("SELECT status FROM outbox WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("FAILED");
        assertThat(store.countPending()).isZero();
    }

    @Test
    void deleteOldSent_removesOnlyOldSentRows() {
        jdbc.update("INSERT INTO outbox (domain, event_type, source, payload, status, created_at, sent_at) " +
                    "VALUES ('USER', 'CREATED', 'INTERNAL', '{}', 'SENT', CURRENT_TIMESTAMP, " +
                    "DATEADD('DAY', -10, CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO outbox (domain, event_type, source, payload, status, created_at, sent_at) " +
                    "VALUES ('USER', 'CREATED', 'INTERNAL', '{}', 'SENT', CURRENT_TIMESTAMP, " +
                    "DATEADD('DAY', -1, CURRENT_TIMESTAMP))");
        store.saveAll(List.of( // PENDING — 삭제 대상 아님
                Outbox.builder().domain("USER").eventType("CREATED").source("INTERNAL").payload("{}").build()
        ));

        store.deleteOldSent();

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class);
        assertThat(total).isEqualTo(2); // 1일전 SENT + 1건 PENDING 만 남음
    }

    @Test
    void countPending_zeroWhenEmpty() {
        assertThat(store.countPending()).isZero();
    }

    /** H2 호환 방언 (테스트 전용). */
    static class H2TestDialect implements OutboxDialect {
        @Override
        public String insertSql() {
            return "INSERT INTO outbox (domain, event_type, source, payload, status, created_at) " +
                   "VALUES (:domain, :eventType, :source, :payload, 'PENDING', CURRENT_TIMESTAMP)";
        }
        @Override
        public String selectPendingWithLockSql() {
            return "SELECT id, seq, domain, event_type, source, payload, status, created_at, sent_at " +
                   "FROM outbox WHERE status = 'PENDING' ORDER BY id ASC LIMIT :limit";
        }
        @Override
        public String deleteOldSentSql() {
            return "DELETE FROM outbox WHERE status = 'SENT' AND sent_at < DATEADD('DAY', -7, CURRENT_TIMESTAMP)";
        }
    }
}

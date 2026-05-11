package io.github.ahnyeongjun.outbox.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ahnyeongjun.outbox.model.Outbox;

/**
 * H2 임베디드 DB 위에서 JdbcOutboxStore 의 공개 SPI(saveAll, countPending, processBatch,
 * deleteOldSent) 동작을 검증한다.
 *
 * <p>락 기제는 SPI 가 아니므로 직접 테스트하지 않고 {@code processBatch} 의 행위를 통해 본다.
 */
class JdbcOutboxStoreTest {

    private EmbeddedDatabase db;
    private JdbcOutboxStore store;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

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
        tx = new TransactionTemplate(new DataSourceTransactionManager(db));
    }

    @Test
    void saveAll_insertsMultipleRowsAsPending() {
        store.saveAll(List.of(
                outbox("USER", "CREATED"),
                outbox("USER", "UPDATED")
        ));

        assertThat(store.countPending()).isEqualTo(2);
    }

    @Test
    void processBatch_handlerSuccess_marksSent() {
        store.saveAll(List.of(outbox("USER", "CREATED"), outbox("USER", "UPDATED")));

        int handled = store.processBatch(tx, 10, batch -> { /* 정상 처리 */ });

        assertThat(handled).isEqualTo(2);
        assertThat(store.countPending()).isZero();
        Long sentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status='SENT' AND sent_at IS NOT NULL", Long.class);
        assertThat(sentCount).isEqualTo(2);
    }

    @Test
    void processBatch_handlerThrows_marksFailed_andDoesNotPropagate() {
        store.saveAll(List.of(outbox("USER", "CREATED"), outbox("USER", "UPDATED")));

        int handled = store.processBatch(tx, 10, batch -> {
            throw new RuntimeException("disk full");
        });

        // 핸들러가 던져도 호출자엔 전파되지 않음 — 대신 FAILED 마킹 + 정상 반환
        assertThat(handled).isEqualTo(2);
        Long failedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status='FAILED'", Long.class);
        assertThat(failedCount).isEqualTo(2);
        assertThat(store.countPending()).isZero();
    }

    @Test
    void processBatch_emptyPending_returnsZero_withoutInvokingHandler() {
        boolean[] handlerCalled = {false};
        int handled = store.processBatch(tx, 10, batch -> handlerCalled[0] = true);

        assertThat(handled).isZero();
        assertThat(handlerCalled[0]).isFalse();
    }

    @Test
    void processBatch_respectsLimit() {
        store.saveAll(List.of(
                outbox("USER", "CREATED"),
                outbox("USER", "UPDATED"),
                outbox("USER", "DELETED")
        ));

        List<Integer> seenSizes = new ArrayList<>();
        int handled = store.processBatch(tx, 2, batch -> seenSizes.add(batch.size()));

        assertThat(handled).isEqualTo(2);
        assertThat(seenSizes).containsExactly(2);
        assertThat(store.countPending()).isEqualTo(1);
    }

    @Test
    void processBatch_handlerReceivesEventsInOrder() {
        store.saveAll(List.of(
                outbox("USER", "CREATED"),
                outbox("USER", "UPDATED"),
                outbox("USER", "DELETED")
        ));

        List<String> seenEventTypes = new ArrayList<>();
        store.processBatch(tx, 10, batch ->
                batch.forEach(o -> seenEventTypes.add(o.getEventType())));

        assertThat(seenEventTypes).containsExactly("CREATED", "UPDATED", "DELETED");
    }

    @Test
    void deleteOldSent_removesOnlyOldSentRows() {
        jdbc.update("INSERT INTO outbox (domain, event_type, source, payload, status, created_at, sent_at) " +
                    "VALUES ('USER', 'CREATED', 'INTERNAL', '{}', 'SENT', CURRENT_TIMESTAMP, " +
                    "DATEADD('DAY', -10, CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO outbox (domain, event_type, source, payload, status, created_at, sent_at) " +
                    "VALUES ('USER', 'CREATED', 'INTERNAL', '{}', 'SENT', CURRENT_TIMESTAMP, " +
                    "DATEADD('DAY', -1, CURRENT_TIMESTAMP))");
        store.saveAll(List.of(outbox("USER", "CREATED")));

        store.deleteOldSent();

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class);
        assertThat(total).isEqualTo(2);
    }

    @Test
    void countPending_zeroWhenEmpty() {
        assertThat(store.countPending()).isZero();
    }

    private Outbox outbox(String domain, String eventType) {
        return Outbox.builder()
                .domain(domain).eventType(eventType).source("INTERNAL").payload("{}")
                .build();
    }

    /** H2 호환 방언 (테스트 전용). */
    static class H2TestDialect implements OutboxDialect {
        @Override public String insertSql() {
            return "INSERT INTO outbox (domain, event_type, source, payload, status, created_at) " +
                   "VALUES (:domain, :eventType, :source, :payload, 'PENDING', CURRENT_TIMESTAMP)";
        }
        @Override public String selectPendingWithLockSql() {
            return "SELECT id, id AS seq, domain, event_type, source, payload, status, created_at, sent_at " +
                   "FROM outbox WHERE status = 'PENDING' ORDER BY id ASC LIMIT :limit";
        }
        @Override public String deleteOldSentSql() {
            return "DELETE FROM outbox WHERE status = 'SENT' AND sent_at < DATEADD('DAY', -7, CURRENT_TIMESTAMP)";
        }
    }
}

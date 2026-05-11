package io.github.ahnyeongjun.outbox.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgreSQLDialectTest {

    @Test
    void defaultSequenceName() {
        PostgreSQLDialect dialect = new PostgreSQLDialect();
        assertThat(dialect.insertSql()).contains("NEXTVAL('outbox_seq_seq')");
    }

    @Test
    void customSequenceName_isUsedInInsert() {
        PostgreSQLDialect dialect = new PostgreSQLDialect("my_seq");
        assertThat(dialect.insertSql()).contains("NEXTVAL('my_seq')");
    }

    @Test
    void insertSql_castsPayloadToJsonb() {
        assertThat(new PostgreSQLDialect().insertSql()).contains(":payload::jsonb");
    }

    @Test
    void selectPendingWithLockSql_orderBySeqWithSkipLocked() {
        String sql = new PostgreSQLDialect().selectPendingWithLockSql();
        assertThat(sql).contains("ORDER BY seq ASC");
        assertThat(sql).contains("LIMIT :limit");
        assertThat(sql).contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql).contains("payload::text AS payload");
    }

    @Test
    void deleteOldSentSql_filtersSentOlderThan7Days() {
        String sql = new PostgreSQLDialect().deleteOldSentSql();
        assertThat(sql).contains("status = 'SENT'");
        assertThat(sql).contains("INTERVAL '7 days'");
    }

    @Test
    void countPendingSql_default() {
        assertThat(new PostgreSQLDialect().countPendingSql())
                .isEqualTo("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'");
    }

    @Test
    void markSentSql_usesIdsInClause() {
        assertThat(new PostgreSQLDialect().markSentSql())
                .contains("id IN (:ids)")
                .contains("status = 'SENT'")
                .contains("sent_at = NOW()");
    }

    @Test
    void markFailedSql_usesIdParameter() {
        assertThat(new PostgreSQLDialect().markFailedSql())
                .contains("WHERE id = :id")
                .contains("status = 'FAILED'");
    }
}

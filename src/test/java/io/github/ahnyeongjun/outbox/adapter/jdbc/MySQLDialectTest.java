package io.github.ahnyeongjun.outbox.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MySQLDialectTest {

    private final MySQLDialect dialect = new MySQLDialect();

    @Test
    void insertSql_doesNotIncludeSeqColumn() {
        String sql = dialect.insertSql();
        assertThat(sql).contains("INSERT INTO outbox");
        assertThat(sql).contains(":payload");
        assertThat(sql).doesNotContain("NEXTVAL");
        assertThat(sql).doesNotContain("::jsonb");
    }

    @Test
    void selectPendingWithLockSql_aliasesSeqAsNull_orderById() {
        String sql = dialect.selectPendingWithLockSql();
        assertThat(sql).contains("NULL AS seq");
        assertThat(sql).contains("ORDER BY id ASC");
        assertThat(sql).contains("LIMIT :limit");
        assertThat(sql).contains("FOR UPDATE SKIP LOCKED");
    }

    @Test
    void deleteOldSentSql_usesMySQLIntervalSyntax() {
        assertThat(dialect.deleteOldSentSql())
                .contains("INTERVAL 7 DAY")
                .contains("status = 'SENT'");
    }

    @Test
    void inheritsDefaultCountSql() {
        assertThat(dialect.countPendingSql())
                .isEqualTo("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'");
    }
}

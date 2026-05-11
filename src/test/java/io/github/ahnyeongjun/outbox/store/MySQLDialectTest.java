package io.github.ahnyeongjun.outbox.store;

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
    void selectPendingWithLockSql_aliasesIdAsSeq_orderById() {
        // OutboxFileWriter 가 long seqFrom = batch.get(0).getSeq() 로 unbox 하므로
        // null 이면 NPE — id 를 seq 로 alias 해서 항상 채워준다.
        String sql = dialect.selectPendingWithLockSql();
        assertThat(sql).contains("id AS seq");
        assertThat(sql).doesNotContain("NULL AS seq");
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

package io.github.ahnyeongjun.outbox.dialect;

public class PostgreSQLDialect implements OutboxDialect {

    private final String sequenceName;

    public PostgreSQLDialect() {
        this("outbox_seq_seq");
    }

    public PostgreSQLDialect(String sequenceName) {
        this.sequenceName = sequenceName;
    }

    @Override
    public String insertSql() {
        return "INSERT INTO outbox (seq, domain, event_type, source, payload, status, created_at) " +
               "VALUES (NEXTVAL('" + sequenceName + "'), :domain, :eventType, :source, :payload::jsonb, 'PENDING', NOW())";
    }

    @Override
    public String selectPendingWithLockSql() {
        return "SELECT id, seq, domain, event_type, source, " +
               "payload::text AS payload, status, created_at, sent_at " +
               "FROM outbox WHERE status = 'PENDING' ORDER BY seq ASC LIMIT :limit FOR UPDATE SKIP LOCKED";
    }

    @Override
    public String deleteOldSentSql() {
        return "DELETE FROM outbox WHERE status = 'SENT' AND sent_at < NOW() - INTERVAL '7 days'";
    }
}

package io.github.ahnyeongjun.outbox.adapter.jdbc;

public class MySQLDialect implements OutboxDialect {

    @Override
    public String insertSql() {
        return "INSERT INTO outbox (domain, event_type, source, payload, status, created_at) " +
               "VALUES (:domain, :eventType, :source, :payload, 'PENDING', NOW())";
    }

    @Override
    public String selectPendingWithLockSql() {
        return "SELECT id, NULL AS seq, domain, event_type, source, " +
               "payload, status, created_at, sent_at " +
               "FROM outbox WHERE status = 'PENDING' ORDER BY id ASC LIMIT :limit FOR UPDATE SKIP LOCKED";
    }

    @Override
    public String deleteOldSentSql() {
        return "DELETE FROM outbox WHERE status = 'SENT' AND sent_at < NOW() - INTERVAL 7 DAY";
    }
}

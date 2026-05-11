package io.github.ahnyeongjun.outbox.store;

public class MySQLDialect implements OutboxDialect {

    @Override
    public String insertSql() {
        return "INSERT INTO outbox (domain, event_type, source, payload, status, created_at) " +
               "VALUES (:domain, :eventType, :source, :payload, 'PENDING', NOW())";
    }

    @Override
    public String selectPendingWithLockSql() {
        // MySQL 은 별도 seq 컬럼을 두지 않고 id 가 시퀀스 역할을 겸함.
        // OutboxFileWriter 가 seq 를 non-null 로 기대하므로 'id AS seq' 로 채워준다.
        return "SELECT id, id AS seq, domain, event_type, source, " +
               "payload, status, created_at, sent_at " +
               "FROM outbox WHERE status = 'PENDING' ORDER BY id ASC LIMIT :limit FOR UPDATE SKIP LOCKED";
    }

    @Override
    public String deleteOldSentSql() {
        return "DELETE FROM outbox WHERE status = 'SENT' AND sent_at < NOW() - INTERVAL 7 DAY";
    }
}

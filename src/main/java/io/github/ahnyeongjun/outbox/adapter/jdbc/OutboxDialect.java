package io.github.ahnyeongjun.outbox.adapter.jdbc;

/**
 * Outbox 테이블 SQL 방언 인터페이스.
 *
 * <p>기본 구현체를 그대로 사용하거나, 커스텀 {@code OutboxDialect} 빈을 등록해 교체할 수 있다.
 * <pre>
 * outbox:
 *   dialect: mysql          # postgresql (기본) | mysql | mariadb
 *   sequence-name: my_seq   # PostgreSQL 전용
 * </pre>
 */
public interface OutboxDialect {

    /** outbox INSERT SQL. NamedParameter(:domain 등) 형식 */
    String insertSql();

    /** PENDING 건수 조회 */
    default String countPendingSql() {
        return "SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'";
    }

    /** PENDING 조회 + 비관적 락. :limit 파라미터 사용 */
    String selectPendingWithLockSql();

    /** SENT 처리. :ids 컬렉션 파라미터 사용 */
    default String markSentSql() {
        return "UPDATE outbox SET status = 'SENT', sent_at = NOW() WHERE id IN (:ids)";
    }

    /** FAILED 처리. :id 파라미터 사용 */
    default String markFailedSql() {
        return "UPDATE outbox SET status = 'FAILED' WHERE id = :id";
    }

    /** 오래된 SENT 삭제 */
    String deleteOldSentSql();
}

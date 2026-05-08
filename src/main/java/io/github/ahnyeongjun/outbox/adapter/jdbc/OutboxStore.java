package io.github.ahnyeongjun.outbox.adapter.jdbc;

import java.util.List;

import io.github.ahnyeongjun.outbox.model.Outbox;

/**
 * Outbox 저장소 인터페이스.
 *
 * <p>기본 구현체({@link JdbcOutboxStore})는 {@link OutboxDialect} 기반 JDBC를 사용한다.
 * JPA, R2DBC 등 다른 영속성 기술을 사용하는 경우 이 인터페이스를 구현해 빈으로 등록하면 된다.
 */
public interface OutboxStore {
    void saveAll(List<Outbox> events);
    long countPending();
    List<Outbox> findPendingWithLock(int limit);
    void markSent(List<Outbox> batch);
    void markFailed(Long id);
    void deleteOldSent();
}

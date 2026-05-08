package io.github.ahnyeongjun.outbox.spi;

import java.util.List;

import io.github.ahnyeongjun.outbox.model.Outbox;

/**
 * Outbox 저장소 SPI.
 *
 * <p>기본 구현체({@link io.github.ahnyeongjun.outbox.adapter.jdbc.JdbcOutboxStore})는
 * JDBC 기반으로 동작한다.
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

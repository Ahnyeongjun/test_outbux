package io.github.ahnyeongjun.outbox.adapter.jpa;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JPA(Hibernate) 기반 {@link OutboxStore} 구현체.
 *
 * <p>락 전략: {@code LockModeType.PESSIMISTIC_WRITE} + {@code jakarta.persistence.lock.timeout = -2}
 * (Hibernate 가 {@code FOR UPDATE SKIP LOCKED} 로 번역). JDBC 어댑터의 SKIP LOCKED 와 동일 의미.
 *
 * <p>활성화: {@code outbox.store-type=jpa} 프로퍼티 설정 + 클래스패스에 JPA + 빈으로 EntityManager 존재.
 */
@Slf4j
@RequiredArgsConstructor
public class JpaOutboxStore implements OutboxStore {

    /** Hibernate 가 PostgreSQL/MySQL 방언에서 SKIP LOCKED 로 번역하는 매직 값. */
    private static final int SKIP_LOCKED_HINT = -2;

    private static final Duration RETENTION = Duration.ofDays(7);

    private final EntityManager em;

    @Override
    @Transactional
    public void saveAll(List<Outbox> events) {
        for (Outbox e : events) {
            OutboxEntity entity = new OutboxEntity();
            entity.setDomain(e.getDomain());
            entity.setEventType(e.getEventType());
            entity.setSource(e.getSource());
            entity.setPayload(e.getPayload());
            entity.setStatus("PENDING");
            em.persist(entity);
        }
    }

    @Override
    public long countPending() {
        return em.createQuery(
                "SELECT COUNT(o) FROM OutboxEntity o WHERE o.status = 'PENDING'", Long.class)
                .getSingleResult();
    }

    @Override
    public int processBatch(TransactionTemplate tx, int limit, Consumer<List<Outbox>> handler) {
        Integer count = tx.execute(status -> {
            List<OutboxEntity> entities = em.createQuery(
                    "SELECT o FROM OutboxEntity o WHERE o.status = 'PENDING' ORDER BY o.id",
                    OutboxEntity.class)
                    .setMaxResults(limit)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setHint("jakarta.persistence.lock.timeout", SKIP_LOCKED_HINT)
                    .getResultList();

            if (entities.isEmpty()) return 0;

            List<Outbox> batch = entities.stream().map(JpaOutboxStore::toModel).toList();
            try {
                handler.accept(batch);
                Instant now = Instant.now();
                for (OutboxEntity e : entities) {
                    e.setStatus("SENT");
                    e.setSentAt(now);
                }
            } catch (RuntimeException ex) {
                log.error("Outbox processBatch handler failed: {}", ex.getMessage(), ex);
                for (OutboxEntity e : entities) {
                    e.setStatus("FAILED");
                }
            }
            // 영속 상태 entity 의 변경은 tx commit 시 자동 flush (dirty checking)
            return entities.size();
        });
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public void deleteOldSent() {
        Instant cutoff = Instant.now().minus(RETENTION);
        em.createQuery("DELETE FROM OutboxEntity o WHERE o.status = 'SENT' AND o.sentAt < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }

    private static Outbox toModel(OutboxEntity e) {
        return Outbox.builder()
                .id(e.getId())
                .seq(e.getId())   // JPA 어댑터는 id = seq (별도 seq 컬럼 없음)
                .domain(e.getDomain())
                .eventType(e.getEventType())
                .source(e.getSource())
                .payload(e.getPayload())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .sentAt(e.getSentAt())
                .build();
    }
}

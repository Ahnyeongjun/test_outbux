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

    /**
     * 캡처된 이벤트들을 PENDING 상태로 영속화한다.
     *
     * <p><b>트랜잭션 전파</b>: 호출하는 비즈니스 트랜잭션에 합류해야 비즈니스 데이터와 outbox 가
     * 원자적으로 커밋된다 (PROPAGATION_REQUIRED 권장). 기본 구현({@code JdbcOutboxStore})은
     * {@code @Transactional} 이 붙어있으므로 호출자가 이미 트랜잭션 안에 있으면 자동 합류한다.
     */
    void saveAll(List<Outbox> events);

    /** PENDING 상태 건수 — 배치 트리거 판단용. */
    long countPending();

    /**
     * 처리 대기 중인 outbox 를 비관 락({@code FOR UPDATE SKIP LOCKED}) 으로 가져온다.
     *
     * <p><b>⚠️ 매우 중요 — 호출 패턴</b>: 반환된 배치를 처리한 후 {@link #markSent(List)} 까지
     * <b>반드시 동일 트랜잭션 안에서</b> 호출해야 한다. 다음과 같이 분리하면 락이 풀린 사이에
     * 다른 인스턴스/스레드가 같은 row 를 잡아 <em>중복 처리</em> 가 발생한다:
     * <pre>{@code
     * // ❌ 잘못된 패턴 — 트랜잭션이 끊겨 락이 풀림
     * List<Outbox> batch = txTemplate.execute(s -> store.findPendingWithLock(100));
     * fileWriter.write(batch);
     * txTemplate.execute(s -> store.markSent(batch));  // 이 시점엔 다른 워커가 같은 row 가져갈 수 있음
     *
     * // ✅ 올바른 패턴 — 한 트랜잭션에서 락 유지하며 처리 + 마킹
     * txTemplate.execute(s -> {
     *     List<Outbox> batch = store.findPendingWithLock(100);
     *     fileWriter.write(batch);
     *     store.markSent(batch);
     *     return null;
     * });
     * }</pre>
     *
     * 기본 스케줄러({@code OutboxScheduler.processOutbox}) 는 메서드 전체가 {@code @Transactional}
     * 이라 이 패턴이 자연스럽게 지켜진다. 직접 호출할 때만 주의.
     *
     * <p><b>활성 트랜잭션 필수</b>: 트랜잭션이 없는 컨텍스트에서 호출하면 일부 DB
     * (PostgreSQL/MySQL 모두 해당) 는 {@code FOR UPDATE} 자체가 의미 없어진다.
     */
    List<Outbox> findPendingWithLock(int limit);

    /**
     * 배치를 SENT 로 마킹.
     *
     * <p>{@link #findPendingWithLock(int)} 와 동일 트랜잭션 안에서 호출되어야 락이 유지된다 —
     * 자세한 내용은 {@code findPendingWithLock} Javadoc 참고.
     */
    void markSent(List<Outbox> batch);

    /** 파일 쓰기 실패 등으로 처리 불가한 항목을 FAILED 로 마킹. 자동 삭제 대상에서 제외된다. */
    void markFailed(Long id);

    /** SENT 상태로 7일 초과된 행 정리. 기본 스케줄러가 매일 02:00 호출. */
    void deleteOldSent();
}

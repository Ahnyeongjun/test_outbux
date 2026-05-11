package io.github.ahnyeongjun.outbox.spi;

import java.util.List;
import java.util.function.Consumer;

import io.github.ahnyeongjun.outbox.model.Outbox;

/**
 * Outbox 저장소 SPI — 동기 RDBMS(JDBC/JPA) 스토어용.
 *
 * <p>인터페이스는 <b>행위(behavior)</b> 만 노출한다 — "어떻게 락을 잡는가" 같은 기제(mechanism)
 * 는 각 구현체의 책임이다. JDBC 구현은 {@code SELECT ... FOR UPDATE SKIP LOCKED} 로,
 * JPA 구현은 {@code LockModeType.PESSIMISTIC_WRITE} 로 동일한 행위를 달성한다.
 *
 * <p>기본 구현체는 {@link io.github.ahnyeongjun.outbox.store.JdbcOutboxStore} 이며,
 * 직접 구현해 빈으로 등록하면 자동 구성을 대체할 수 있다.
 */
public interface OutboxStore {

    /**
     * 캡처된 이벤트들을 PENDING 상태로 영속화한다.
     *
     * <p><b>트랜잭션 전파</b>: 호출하는 비즈니스 트랜잭션에 합류해야 비즈니스 데이터와 outbox 가
     * 원자적으로 커밋된다 (PROPAGATION_REQUIRED 권장). 기본 구현({@code JdbcOutboxStore})은
     * {@code @Transactional} 이 붙어있어 호출자가 트랜잭션 안에 있으면 자동 합류한다.
     */
    void saveAll(List<Outbox> events);

    /** PENDING 상태 건수 — 배치 트리거 판단용. */
    long countPending();

    /**
     * 락 + 처리 + SENT/FAILED 마킹을 한 트랜잭션으로 원자적으로 수행한다.
     *
     * <p>이 메서드가 OutboxStore 의 <b>유일한 권장 진입점</b>이다. 트랜잭션 경계·락 전략·실패
     * 마킹은 모두 구현체 안에 캡슐화되어 있으므로 호출자는 비즈니스 의도(limit, handler)만 신경
     * 쓰면 된다.
     *
     * <p>흐름:
     * <ol>
     *   <li>구현체가 자체 트랜잭션 시작
     *   <li>PENDING 행 {@code limit} 건을 비관 락으로 가져옴
     *   <li>{@code handler} 가 배치를 처리 (파일 쓰기, 외부 전송 등)
     *   <li>성공 시 SENT 로 마킹, 예외 시 각 행을 FAILED 로 마킹
     *   <li>커밋 — 락 해제
     * </ol>
     *
     * <p>예외 정책: {@code handler} 가 던지는 예외는 catch 되어 배치 전체가 FAILED 로 마킹되며
     * 호출자에게 전파되지 않는다 (트랜잭션은 정상 커밋).
     *
     * @param limit   한 번에 가져올 최대 건수
     * @param handler 배치 처리 로직 (파일 쓰기 등)
     * @return 처리된 건수 (PENDING 이 없으면 0)
     */
    int processBatch(int limit, Consumer<List<Outbox>> handler);

    /** SENT 상태로 7일 초과된 행 정리. 기본 스케줄러가 매일 02:00 호출. */
    void deleteOldSent();
}

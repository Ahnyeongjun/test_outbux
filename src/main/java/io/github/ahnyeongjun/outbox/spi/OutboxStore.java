package io.github.ahnyeongjun.outbox.spi;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.transaction.support.TransactionTemplate;

import io.github.ahnyeongjun.outbox.model.Outbox;

/**
 * Outbox 저장소 SPI.
 *
 * <p>기본 구현체({@link io.github.ahnyeongjun.outbox.adapter.jdbc.JdbcOutboxStore})는
 * JDBC 기반으로 동작한다.
 * JPA, R2DBC 등 다른 영속성 기술을 사용하는 경우 이 인터페이스를 구현해 빈으로 등록하면 된다.
 *
 * <p><b>직접 호출 시 권장 진입점</b>: {@link #processBatch(TransactionTemplate, int, Consumer)} —
 * 락+처리+SENT/FAILED 마킹을 한 트랜잭션으로 안전하게 묶어주므로 락 분리 footgun 을 피할 수 있다.
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
     * <p><b>⚠️ 저수준 API — 직접 사용을 권장하지 않는다.</b> {@link #markSent(List)} 와 동일
     * 트랜잭션 안에서 호출하지 않으면 락이 풀려 다른 워커가 같은 row 를 잡아 중복 처리한다.
     * 안전한 호출은 {@link #processBatch(TransactionTemplate, int, Consumer)} 를 쓰면 된다.
     */
    List<Outbox> findPendingWithLock(int limit);

    /**
     * 배치를 SENT 로 마킹.
     *
     * <p>{@link #findPendingWithLock(int)} 와 동일 트랜잭션 안에서 호출되어야 락이 유지된다 —
     * 분리하면 race condition 발생. 안전한 패턴은 {@link #processBatch} 참조.
     */
    void markSent(List<Outbox> batch);

    /** 파일 쓰기 실패 등으로 처리 불가한 항목을 FAILED 로 마킹. 자동 삭제 대상에서 제외된다. */
    void markFailed(Long id);

    /** SENT 상태로 7일 초과된 행 정리. 기본 스케줄러가 매일 02:00 호출. */
    void deleteOldSent();

    /**
     * 락+처리+상태 마킹을 한 트랜잭션으로 안전하게 묶어 실행하는 권장 진입점.
     *
     * <p>흐름:
     * <ol>
     *   <li>{@code findPendingWithLock(limit)} 으로 PENDING 행을 비관 락으로 가져옴
     *   <li>{@code handler} 가 배치를 처리 (파일 쓰기, 외부 전송 등)
     *   <li>성공 시 {@code markSent}, 예외 시 각 행을 {@code markFailed} 후 트랜잭션 커밋
     * </ol>
     *
     * <p>이 메서드는 락 유지 구간 안에서 모든 마킹을 끝내므로 다중 워커가 같은 행을 중복 처리하지
     * 않는다. {@link #findPendingWithLock} 와 {@link #markSent} 를 따로 호출하는 패턴은
     * race condition 위험이 있으니 가급적 이 메서드를 사용한다.
     *
     * <p>예외 정책: {@code handler} 가 던지는 예외는 catch 되어 배치 전체가 FAILED 로 마킹되며
     * 호출자에게 전파되지 않는다 (트랜잭션은 정상 커밋). 호출자는 반환값으로 처리 결과를 안다.
     *
     * @param tx      트랜잭션 경계를 만들 {@link TransactionTemplate}
     * @param limit   한 번에 가져올 최대 건수
     * @param handler 배치 처리 로직 (파일 쓰기 등)
     * @return 처리된 건수 (PENDING 이 없으면 0). 마킹이 SENT 인지 FAILED 인지는 상관없이
     *         실제로 다룬 건수만 반환.
     */
    default int processBatch(TransactionTemplate tx, int limit, Consumer<List<Outbox>> handler) {
        Integer count = tx.execute(status -> {
            List<Outbox> batch = findPendingWithLock(limit);
            if (batch.isEmpty()) return 0;
            try {
                handler.accept(batch);
                markSent(batch);
            } catch (RuntimeException e) {
                // 같은 tx 안에서 FAILED 마킹 후 정상 commit → 락 풀고 다음 워커가 잡아도
                // 이미 FAILED 라 PENDING 쿼리에 안 잡힘.
                batch.forEach(o -> markFailed(o.getId()));
            }
            return batch.size();
        });
        return count == null ? 0 : count;
    }
}

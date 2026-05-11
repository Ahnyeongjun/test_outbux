package io.github.ahnyeongjun.outbox.observability;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.ahnyeongjun.outbox.spi.OutboxStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * Outbox 운영 메트릭 — Micrometer {@link MeterRegistry} 에 노출.
 *
 * <p>사용자가 Prometheus/Datadog/CloudWatch 어떤 backend 든 Spring Boot 의 Actuator + 해당
 * micrometer-registry-* 의존성을 추가하면 자동으로 수집됨. 본 라이브러리는 측정점만 제공.
 *
 * <p>노출 메트릭:
 * <ul>
 *   <li>{@code outbox.events.captured} (Counter, tags=domain,event_type) — 인터셉터/리스너에서
 *       캡처된 이벤트 수
 *   <li>{@code outbox.batch.processed} (Timer) — processBatch 1회 처리 시간 + 호출 수
 *   <li>{@code outbox.batch.size} (DistributionSummary) — 처리된 배치 크기 분포
 *   <li>{@code outbox.batch.trigger} (Counter, tags=reason) — 스케줄러 트리거 이유 분포 (time/size)
 *   <li>{@code outbox.file.write} (Timer) — gzip 파일 쓰기 시간
 *   <li>{@code outbox.file.bytes} (DistributionSummary) — 생성된 파일 크기 분포
 *   <li>{@code outbox.pending} (Gauge) — 현재 PENDING 행 수 (Prometheus 스크랩마다 DB 조회)
 *   <li>{@code outbox.failed.total} (Counter) — FAILED 마킹된 누적 건수
 * </ul>
 */
public class OutboxMetrics {

    private final MeterRegistry registry;
    private final Timer batchProcessingTimer;
    private final DistributionSummary batchSizeSummary;
    private final Timer fileWriteTimer;
    private final DistributionSummary fileBytesSummary;
    private final Counter failedCounter;
    private final AtomicLong lastObservedPending = new AtomicLong(0);

    public OutboxMetrics(MeterRegistry registry, OutboxStore outboxStore) {
        this.registry = registry;
        this.batchProcessingTimer = Timer.builder("outbox.batch.processed")
                .description("Time spent in OutboxStore.processBatch")
                .register(registry);
        this.batchSizeSummary = DistributionSummary.builder("outbox.batch.size")
                .description("Number of events per processed batch")
                .baseUnit("events")
                .register(registry);
        this.fileWriteTimer = Timer.builder("outbox.file.write")
                .description("Time spent writing gzip outbox file")
                .register(registry);
        this.fileBytesSummary = DistributionSummary.builder("outbox.file.bytes")
                .description("Size distribution of generated outbox files")
                .baseUnit("bytes")
                .register(registry);
        this.failedCounter = Counter.builder("outbox.failed.total")
                .description("Outbox rows marked FAILED")
                .register(registry);
        Gauge.builder("outbox.pending", outboxStore, store -> {
                    long v = store.countPending();
                    lastObservedPending.set(v);
                    return v;
                })
                .description("Number of PENDING outbox rows")
                .register(registry);
    }

    /** 캡처 시점 — 인터셉터/리스너가 호출. domain/event_type 태그로 분포 가시화. */
    public void recordCaptured(String domain, String eventType) {
        Counter.builder("outbox.events.captured")
                .description("Outbox events captured by interceptor/listener")
                .tags(Tags.of("domain", domain, "event_type", eventType))
                .register(registry)
                .increment();
    }

    /** 스케줄러 트리거 — time/size 별 분포. */
    public void recordTrigger(boolean timeTriggered, boolean sizeTriggered) {
        String reason = sizeTriggered ? (timeTriggered ? "both" : "size") : "time";
        Counter.builder("outbox.batch.trigger")
                .description("OutboxScheduler trigger reason distribution")
                .tags("reason", reason)
                .register(registry)
                .increment();
    }

    /** processBatch 1회 — duration + 처리 건수. */
    public void recordBatch(long durationNanos, int processedCount) {
        batchProcessingTimer.record(durationNanos, TimeUnit.NANOSECONDS);
        if (processedCount > 0) {
            batchSizeSummary.record(processedCount);
        }
    }

    /** OutboxFileWriter.write 1회 — duration + 파일 크기. */
    public void recordFileWrite(long durationNanos, long bytes) {
        fileWriteTimer.record(durationNanos, TimeUnit.NANOSECONDS);
        fileBytesSummary.record(bytes);
    }

    /** markFailed 발생 — count 만큼 증가. */
    public void recordFailed(int count) {
        failedCounter.increment(count);
    }

    /** 직전 관측된 PENDING 값 — 테스트/디버깅용 (실시간 쿼리 아님). */
    public long lastObservedPending() {
        return lastObservedPending.get();
    }
}

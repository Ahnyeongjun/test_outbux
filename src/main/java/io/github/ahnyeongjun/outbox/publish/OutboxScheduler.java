package io.github.ahnyeongjun.outbox.publish;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 파일 변환 배치.
 * 시간 트리거 OR 건수 트리거 중 먼저 충족되는 조건으로 실행.
 *
 * <p>락+처리+마킹은 {@link OutboxStore#processBatch} 가 한 트랜잭션으로 묶어 안전성을 보장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxScheduler {

    private final OutboxProperties properties;
    private final OutboxStore outboxStore;
    private final OutboxFileWriter outboxFileWriter;

    private final AtomicReference<Instant> lastFlush = new AtomicReference<>(Instant.now());

    @Scheduled(fixedDelayString = "${outbox.batch.check-interval-ms:5000}")
    public void processOutbox() {
        long pending = outboxStore.countPending();
        boolean timeTriggered = Duration.between(lastFlush.get(), Instant.now()).toMillis()
                >= properties.getBatch().getTimeTriggerMs();
        boolean sizeTriggered = pending >= properties.getBatch().getSize();

        if (!timeTriggered && !sizeTriggered) return;

        int handled = outboxStore.processBatch(
                properties.getBatch().getSize(),
                outboxFileWriter::write
        );
        lastFlush.set(Instant.now());

        if (handled > 0) {
            log.info("Outbox processed {} events (time={}, size={})", handled, timeTriggered, sizeTriggered);
        }
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanUpSent() {
        outboxStore.deleteOldSent();
        log.info("Outbox cleanup: deleted SENT events older than 7 days");
    }
}

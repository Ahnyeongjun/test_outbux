package io.github.ahnyeongjun.outbox.publish;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 파일 변환 배치.
 * 시간 트리거 OR 건수 트리거 중 먼저 충족되는 조건으로 실행.
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
    @Transactional
    public void processOutbox() {
        long pending = outboxStore.countPending();
        boolean timeTriggered = Duration.between(lastFlush.get(), Instant.now()).toMillis()
                >= properties.getBatch().getTimeTriggerMs();
        boolean sizeTriggered = pending >= properties.getBatch().getSize();

        if (!timeTriggered && !sizeTriggered) return;

        List<Outbox> batch = outboxStore.findPendingWithLock(properties.getBatch().getSize());
        if (batch.isEmpty()) { lastFlush.set(Instant.now()); return; }

        try {
            outboxFileWriter.write(batch);
            outboxStore.markSent(batch);
            lastFlush.set(Instant.now());
            log.info("Outbox flushed {} events (time={}, size={})", batch.size(), timeTriggered, sizeTriggered);
        } catch (Exception e) {
            batch.forEach(o -> outboxStore.markFailed(o.getId()));
            log.error("Outbox flush failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanUpSent() {
        outboxStore.deleteOldSent();
        log.info("Outbox cleanup: deleted SENT events older than 7 days");
    }
}

package io.github.ahnyeongjun.outbox.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.repository.OutboxRepository;
import io.github.ahnyeongjun.outbox.writer.OutboxFileWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox ???åÏùº Î≥Ä??Î∞∞Ïπò.
 * ?úÍ∞Ñ ?∏Î¶¨Í±?OR Í±¥Ïàò ?∏Î¶¨Í±?Ï§?Î®ºÏ? Ï∂©Ï°±?òÎäî Ï°∞Í±¥?ºÎ°ú ?§Ìñâ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxScheduler {

    @Value("${outbox.batch.size:1000}")
    private int batchSize;

    @Value("${outbox.batch.time-trigger-ms:60000}")
    private long timeTriggerMs;

    private final OutboxRepository outboxRepository;
    private final OutboxFileWriter outboxFileWriter;

    private final AtomicReference<Instant> lastFlush = new AtomicReference<>(Instant.now());

    @Scheduled(fixedDelayString = "${outbox.batch.check-interval-ms:5000}")
    @Transactional
    public void processOutbox() {
        long pending = outboxRepository.countPending();
        boolean timeTriggered = Duration.between(lastFlush.get(), Instant.now()).toMillis() >= timeTriggerMs;
        boolean sizeTriggered = pending >= batchSize;

        if (!timeTriggered && !sizeTriggered) return;

        List<Outbox> batch = outboxRepository.findPendingWithLock(batchSize);
        if (batch.isEmpty()) { lastFlush.set(Instant.now()); return; }

        try {
            outboxFileWriter.write(batch);
            outboxRepository.markSent(batch);
            lastFlush.set(Instant.now());
            log.info("Outbox flushed {} events (time={}, size={})", batch.size(), timeTriggered, sizeTriggered);
        } catch (Exception e) {
            batch.forEach(o -> outboxRepository.markFailed(o.getId()));
            log.error("Outbox flush failed: {}", e.getMessage(), e);
        }
    }

    /** SENT 7??????†ú */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanUpSent() {
        outboxRepository.deleteOldSent();
        log.info("Outbox cleanup: deleted SENT events older than 7 days");
    }
}

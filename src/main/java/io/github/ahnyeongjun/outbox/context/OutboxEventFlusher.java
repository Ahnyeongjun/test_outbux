package io.github.ahnyeongjun.outbox.context;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.store.OutboxStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MyBatis 인터셉터와 JPA 리스너가 공유하는 이벤트 캡처·플러시 로직.
 * 트랜잭션 beforeCommit 에 일괄 저장, 트랜잭션 없으면 즉시 저장.
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxEventFlusher {

    private final OutboxStore store;

    public void capture(OutboxContextData ctx, Outbox event) {
        ctx.addEvent(event);
        registerSyncIfNeeded(ctx);
    }

    private void registerSyncIfNeeded(OutboxContextData ctx) {
        if (ctx.isSyncRegistered()) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            flush(ctx);
            OutboxContext.clear();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!readOnly) flush(ctx);
            }
            @Override
            public void afterCompletion(int status) {
                OutboxContext.clear();
            }
        });
        ctx.markSyncRegistered();
    }

    private void flush(OutboxContextData ctx) {
        if (!ctx.hasPendingEvents()) return;
        try {
            store.saveAll(ctx.getPendingEvents());
            log.debug("Outbox flushed {} events", ctx.getPendingEvents().size());
        } catch (Exception e) {
            log.error("Outbox flush failed: {}", e.getMessage(), e);
        }
    }
}

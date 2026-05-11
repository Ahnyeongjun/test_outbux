package io.github.ahnyeongjun.outbox.capture;

import java.util.Map;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.spi.OutboxConverter;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이벤트 캡처·플러시 공통 로직.
 *
 * <p>MyBatis 인터셉터와 JPA 리스너의 공통 관심사(suppress 체크, 테이블 필터, 컨버터 조회,
 * 트랜잭션 동기화)를 한 곳에서 처리한다. 각 캡처 주체는 테이블명·엔티티·이벤트타입만 전달하면 된다.
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxEventFlusher {

    private final OutboxStore store;
    private final OutboxProperties properties;
    private final Map<String, OutboxConverter> converters;

    /** 프레임워크별 캡처 진입점 — 테이블명 기반 자동 감지·저장 */
    public void capture(String rawTableName, Object entity, String defaultEventType) {
        if (OutboxContext.isSuppressed()) return;

        String tableName = normalizeTableName(rawTableName);
        if (!properties.getTables().contains(tableName)) return;

        String domain = tableName.toUpperCase();
        OutboxContextData ctx = OutboxContext.getOrCreate();
        String eventType = ctx.hasCustomEventType() ? ctx.getCustomEventType() : defaultEventType;

        String beanName = domain.toLowerCase().replace("_", "") + "OutboxConverter";
        OutboxConverter converter = converters.getOrDefault(beanName,
                converters.get("defaultOutboxConverter"));

        if (converter == null) {
            log.warn("OutboxConverter not found for domain={}", domain);
            return;
        }

        enqueue(ctx, converter.convert(entity, domain, eventType));
    }

    /** OutboxContext 에 이벤트 적재 후 트랜잭션 동기화 등록 */
    public void enqueue(OutboxContextData ctx, Outbox event) {
        ctx.addEvent(event);
        registerSyncIfNeeded(ctx);
    }

    private String normalizeTableName(String rawTableName) {
        int dot = rawTableName.lastIndexOf('.');
        return (dot >= 0 ? rawTableName.substring(dot + 1) : rawTableName).toLowerCase();
    }

    private void registerSyncIfNeeded(OutboxContextData ctx) {
        if (ctx.isSyncRegistered()) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            flushPending(ctx);
            OutboxContext.clear();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!readOnly) flushPending(ctx);
            }
            @Override
            public void afterCompletion(int status) {
                OutboxContext.clear();
            }
        });
        ctx.markSyncRegistered();
    }

    private void flushPending(OutboxContextData ctx) {
        if (!ctx.hasPendingEvents()) return;
        try {
            store.saveAll(ctx.getPendingEvents());
            log.debug("Outbox flushed {} events", ctx.getPendingEvents().size());
        } catch (Exception e) {
            log.error("Outbox flush failed: {}", e.getMessage(), e);
        }
    }
}

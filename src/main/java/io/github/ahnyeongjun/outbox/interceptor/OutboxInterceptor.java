package io.github.ahnyeongjun.outbox.interceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.beans.factory.ObjectProvider;

import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.context.OutboxContext;
import io.github.ahnyeongjun.outbox.context.OutboxContextData;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.model.OutboxConverter;
import io.github.ahnyeongjun.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MyBatis Executor.update() 인터셉터.
 *
 * <p>mapper namespace → 테이블명 → outbox.tables 포함 여부 확인 → 이벤트 캡처
 *
 * <pre>
 * com.example.mapper.UserMapper            → user             → USER
 * com.example.mapper.OrderItemMapper       → order_item       → ORDER_ITEM
 * com.example.mapper.ProductCategoryMapper → product_category → PRODUCT_CATEGORY
 * </pre>
 */
@Slf4j
@Intercepts({
    @Signature(type = Executor.class, method = "update",
               args = {MappedStatement.class, Object.class})
})
@RequiredArgsConstructor
public class OutboxInterceptor implements Interceptor {

    private static final String OUTBOX_MAPPER_PREFIX = "io.github.ahnyeongjun.outbox.mapper.OutboxMapper";

    private final OutboxProperties properties;
    private final Map<String, OutboxConverter> converters;
    private final ObjectProvider<OutboxRepository> outboxRepositoryProvider;

    /** mapper namespace → 테이블명 캐시 */
    private final Map<String, String> tableNameCache = new ConcurrentHashMap<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();

        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];

        // OutboxMapper 자기참조 방지
        if (ms.getId().startsWith(OUTBOX_MAPPER_PREFIX)) return result;

        // suppress 모드 (폐쇄망 수신 이벤트 재사용 시)
        if (OutboxContext.isSuppressed()) return result;

        SqlCommandType sqlType = ms.getSqlCommandType();
        if (sqlType == SqlCommandType.SELECT || sqlType == SqlCommandType.UNKNOWN) return result;

        String tableName = resolveTableName(ms);
        if (!properties.getTables().contains(tableName)) return result;

        String domain    = tableName.toUpperCase();
        String eventType = resolveEventType(sqlType);
        Object parameter = invocation.getArgs()[1];

        // 도메인 전용 컨버터가 없으면 기본 컨버터 폴백
        String beanName = domain.toLowerCase().replace("_", "") + "OutboxConverter";
        OutboxConverter converter = converters.getOrDefault(beanName,
                converters.get("defaultOutboxConverter"));

        if (converter == null) {
            log.warn("OutboxConverter not found for domain={}", domain);
            return result;
        }

        OutboxContextData ctx = OutboxContext.getOrCreate();
        ctx.addEvent(converter.convert(parameter, domain, eventType));
        registerSyncIfNeeded(ctx);

        return result;
    }

    /**
     * mapper namespace → 테이블명.
     * com.example.mapper.ProductCategoryMapper → product_category
     */
    private String resolveTableName(MappedStatement ms) {
        String namespace = ms.getId().substring(0, ms.getId().lastIndexOf('.'));
        return tableNameCache.computeIfAbsent(namespace, ns -> {
            String mapperClass = ns.substring(ns.lastIndexOf('.') + 1);          // McAuthGrpMenuFuncMpnMapper
            String withoutSuffix = mapperClass.replaceAll("Mapper$", "");        // McAuthGrpMenuFuncMpn
            return withoutSuffix
                    .replaceAll("([a-z])([A-Z])", "$1_$2")                       // Mc_Auth_Grp_Menu_Func_Mpn
                    .toLowerCase();                                               // mc_auth_grp_menu_func_mpn
        });
    }

    private void registerSyncIfNeeded(OutboxContextData ctx) {
        if (ctx.isSyncRegistered()) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            flushEvents(ctx);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!readOnly) flushEvents(ctx);
            }
            @Override
            public void afterCompletion(int status) {
                OutboxContext.clear();
            }
        });
        ctx.markSyncRegistered();
    }

    private void flushEvents(OutboxContextData ctx) {
        if (!ctx.hasPendingEvents()) return;
        try {
            outboxRepositoryProvider.getObject().saveAll(ctx.getPendingEvents());
            log.debug("Outbox flushed {} events", ctx.getPendingEvents().size());
        } catch (Exception e) {
            log.error("Outbox flush failed: {}", e.getMessage(), e);
        }
    }

    private String resolveEventType(SqlCommandType type) {
        OutboxContextData ctx = OutboxContext.get();
        if (ctx != null && ctx.hasCustomEventType()) return ctx.getCustomEventType();
        return switch (type) {
            case INSERT -> "CREATED";
            case UPDATE -> "UPDATED";
            case DELETE -> "DELETED";
            default     -> "CHANGED";
        };
    }
}

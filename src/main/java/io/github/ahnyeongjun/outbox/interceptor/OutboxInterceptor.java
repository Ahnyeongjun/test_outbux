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

import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.context.OutboxContext;
import io.github.ahnyeongjun.outbox.context.OutboxContextData;
import io.github.ahnyeongjun.outbox.context.OutboxEventFlusher;
import io.github.ahnyeongjun.outbox.model.OutboxConverter;
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

    private final OutboxProperties properties;
    private final Map<String, OutboxConverter> converters;
    private final OutboxEventFlusher flusher;

    private final Map<String, String> tableNameCache = new ConcurrentHashMap<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();

        if (OutboxContext.isSuppressed()) return result;

        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        SqlCommandType sqlType = ms.getSqlCommandType();
        if (sqlType == SqlCommandType.SELECT || sqlType == SqlCommandType.UNKNOWN) return result;

        String tableName = resolveTableName(ms);
        if (!properties.getTables().contains(tableName)) return result;

        String domain    = tableName.toUpperCase();
        String eventType = resolveEventType(sqlType);
        Object parameter = invocation.getArgs()[1];

        String beanName = domain.toLowerCase().replace("_", "") + "OutboxConverter";
        OutboxConverter converter = converters.getOrDefault(beanName,
                converters.get("defaultOutboxConverter"));

        if (converter == null) {
            log.warn("OutboxConverter not found for domain={}", domain);
            return result;
        }

        flusher.capture(OutboxContext.getOrCreate(), converter.convert(parameter, domain, eventType));

        return result;
    }

    /**
     * mapper namespace → 테이블명.
     * com.example.mapper.ProductCategoryMapper → product_category
     */
    private String resolveTableName(MappedStatement ms) {
        String namespace = ms.getId().substring(0, ms.getId().lastIndexOf('.'));
        return tableNameCache.computeIfAbsent(namespace, ns -> {
            String mapperClass = ns.substring(ns.lastIndexOf('.') + 1);
            String withoutSuffix = mapperClass.replaceAll("Mapper$", "");
            return withoutSuffix
                    .replaceAll("([a-z])([A-Z])", "$1_$2")
                    .toLowerCase();
        });
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

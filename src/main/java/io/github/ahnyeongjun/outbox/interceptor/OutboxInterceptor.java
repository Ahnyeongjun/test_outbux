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

import io.github.ahnyeongjun.outbox.context.OutboxEventFlusher;
import lombok.RequiredArgsConstructor;

/**
 * MyBatis Executor.update() 인터셉터.
 * mapper namespace → 테이블명 변환 후 {@link OutboxEventFlusher}에 위임.
 *
 * <pre>
 * com.example.mapper.OrderItemMapper → order_item
 * </pre>
 */
@Intercepts({
    @Signature(type = Executor.class, method = "update",
               args = {MappedStatement.class, Object.class})
})
@RequiredArgsConstructor
public class OutboxInterceptor implements Interceptor {

    private final OutboxEventFlusher flusher;

    private final Map<String, String> tableNameCache = new ConcurrentHashMap<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();

        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        SqlCommandType sqlType = ms.getSqlCommandType();
        if (sqlType == SqlCommandType.SELECT || sqlType == SqlCommandType.UNKNOWN) return result;

        flusher.capture(resolveTableName(ms), invocation.getArgs()[1], resolveEventType(sqlType));

        return result;
    }

    private String resolveTableName(MappedStatement ms) {
        String namespace = ms.getId().substring(0, ms.getId().lastIndexOf('.'));
        return tableNameCache.computeIfAbsent(namespace, ns -> {
            String mapperClass = ns.substring(ns.lastIndexOf('.') + 1);
            return mapperClass.replaceAll("Mapper$", "")
                    .replaceAll("([a-z])([A-Z])", "$1_$2")
                    .toLowerCase();
        });
    }

    private String resolveEventType(SqlCommandType type) {
        return switch (type) {
            case INSERT -> "CREATED";
            case UPDATE -> "UPDATED";
            case DELETE -> "DELETED";
            default     -> "CHANGED";
        };
    }
}

package io.github.ahnyeongjun.outbox.listener;

import java.util.Map;

import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;

import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.context.OutboxContext;
import io.github.ahnyeongjun.outbox.context.OutboxContextData;
import io.github.ahnyeongjun.outbox.context.OutboxEventFlusher;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.model.OutboxConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JPA/Hibernate 엔티티 변경 이벤트를 캡처하는 리스너.
 *
 * <p>MyBatis {@code OutboxInterceptor}의 JPA 대응체.
 * {@link OutboxHibernateIntegrator}를 통해 Hibernate 이벤트 시스템에 등록된다.
 * 감지 기준은 {@code outbox.tables}에 선언된 테이블명과 엔티티 매핑 테이블명의 일치 여부.
 *
 * <pre>
 * outbox:
 *   tables: [user, order_item]
 *
 * {@literal @}Entity
 * {@literal @}Table(name = "user")        ← 감지됨
 * public class UserEntity { ... }
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
public class HibernateOutboxListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private final OutboxProperties properties;
    private final Map<String, OutboxConverter> converters;
    private final OutboxEventFlusher flusher;

    @Override
    public void onPostInsert(PostInsertEvent event) {
        captureEvent(event.getPersister().getTableName(), event.getEntity(), "CREATED");
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        captureEvent(event.getPersister().getTableName(), event.getEntity(), "UPDATED");
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        captureEvent(event.getPersister().getTableName(), event.getEntity(), "DELETED");
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void captureEvent(String rawTableName, Object entity, String defaultEventType) {
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

        Outbox outbox = converter.convert(entity, domain, eventType);
        flusher.capture(ctx, outbox);
    }

    /** "public.user_table" → "user_table" */
    private String normalizeTableName(String rawTableName) {
        int dot = rawTableName.lastIndexOf('.');
        return (dot >= 0 ? rawTableName.substring(dot + 1) : rawTableName).toLowerCase();
    }
}

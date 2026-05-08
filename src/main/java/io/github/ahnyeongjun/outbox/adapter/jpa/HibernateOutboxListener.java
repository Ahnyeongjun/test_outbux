package io.github.ahnyeongjun.outbox.adapter.jpa;

import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;

import io.github.ahnyeongjun.outbox.capture.OutboxEventFlusher;
import lombok.RequiredArgsConstructor;

/**
 * JPA/Hibernate 엔티티 변경 이벤트를 캡처하는 리스너.
 * {@link OutboxHibernateIntegrator}를 통해 Hibernate 이벤트 시스템에 등록되며,
 * 실제 처리는 {@link OutboxEventFlusher}에 위임한다.
 */
@RequiredArgsConstructor
public class HibernateOutboxListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private final OutboxEventFlusher flusher;

    @Override
    public void onPostInsert(PostInsertEvent event) {
        flusher.capture(event.getPersister().getTableName(), event.getEntity(), "CREATED");
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        flusher.capture(event.getPersister().getTableName(), event.getEntity(), "UPDATED");
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        flusher.capture(event.getPersister().getTableName(), event.getEntity(), "DELETED");
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }
}

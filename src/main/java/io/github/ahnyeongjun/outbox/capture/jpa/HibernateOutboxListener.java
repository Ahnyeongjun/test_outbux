package io.github.ahnyeongjun.outbox.capture.jpa;

import org.hibernate.action.spi.BeforeTransactionCompletionProcess;
import org.hibernate.event.spi.AbstractEvent;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.Joinable;

import io.github.ahnyeongjun.outbox.capture.OutboxContext;
import io.github.ahnyeongjun.outbox.capture.OutboxContextData;
import io.github.ahnyeongjun.outbox.capture.OutboxEventFlusher;
import lombok.RequiredArgsConstructor;

/**
 * JPA/Hibernate 엔티티 변경 이벤트를 캡처하는 리스너.
 *
 * <p><b>Flush 타이밍 이슈 회피</b>: INSERT 는 IDENTITY 키 생성 때문에 {@code repo.save()} 시점에
 * 즉시 flush 되어 PostInsertEvent 가 비즈니스 로직 중 발생 → TransactionSynchronization 의
 * {@code beforeCommit} 훅으로 안전하게 outbox INSERT. 반면 UPDATE/DELETE 는 dirty checking 이라
 * Hibernate 가 {@code doCommit()} 의 {@code em.flush()} 에서만 flush 함 → 그때 등록한
 * TransactionSynchronization 의 beforeCommit 은 이미 지나간 시점이라 호출 안 됨 → outbox INSERT 누락.
 *
 * <p>해결: 모든 이벤트 발사 시 Hibernate 의 {@link BeforeTransactionCompletionProcess} 도 함께
 * 등록한다. 이 훅은 <em>flush 이후 JDBC commit 이전</em> 에 발사되므로 어떤 이벤트든 안전.
 * Spring sync 의 beforeCommit 과 중복 발사돼도 {@link OutboxEventFlusher#flushAccumulatedEvents}
 * 가 idempotent (이벤트 리스트 비움) 이라 실제 INSERT 는 한 번만.
 */
@RequiredArgsConstructor
public class HibernateOutboxListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private final OutboxEventFlusher flusher;

    @Override
    public void onPostInsert(PostInsertEvent event) {
        flusher.capture(tableName(event.getPersister()), event.getEntity(), "CREATED");
        registerHibernateFlushHook(event);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        flusher.capture(tableName(event.getPersister()), event.getEntity(), "UPDATED");
        registerHibernateFlushHook(event);
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        flusher.capture(tableName(event.getPersister()), event.getEntity(), "DELETED");
        registerHibernateFlushHook(event);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    /**
     * 현재 Hibernate 세션의 ActionQueue 에 "flush 후 commit 전" 훅 등록. 한 tx 당 한 번만 등록 —
     * {@link OutboxContextData} 의 hibernateHookRegistered 플래그로 중복 방지.
     */
    private void registerHibernateFlushHook(AbstractEvent event) {
        OutboxContextData ctx = OutboxContext.get();
        if (ctx == null || ctx.isHibernateFlushHookRegistered()) return;
        ctx.markHibernateFlushHookRegistered();

        EventSource session = event.getSession();
        BeforeTransactionCompletionProcess process =
                sessionImpl -> flusher.flushAccumulatedEvents(ctx);
        session.getActionQueue().registerProcess(process);
    }

    /** Hibernate 6 에서 {@code EntityPersister.getTableName()} 이 제거되어 {@link Joinable} 캐스팅으로 우회. */
    private static String tableName(EntityPersister persister) {
        return ((Joinable) persister).getTableName();
    }
}

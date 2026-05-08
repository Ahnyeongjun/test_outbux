package io.github.ahnyeongjun.outbox.listener;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

import lombok.RequiredArgsConstructor;

/**
 * {@link HibernateOutboxListener}를 Hibernate 이벤트 시스템에 등록하는 통합자.
 * {@code HibernatePropertiesCustomizer}를 통해 {@code hibernate.integrator_provider}로 연결된다.
 */
@RequiredArgsConstructor
public class OutboxHibernateIntegrator implements Integrator {

    private final HibernateOutboxListener listener;

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                          SessionFactoryImplementor sessionFactory) {
        EventListenerRegistry registry = sessionFactory.getServiceRegistry()
                .getService(EventListenerRegistry.class);
        if (registry == null) return;
        registry.appendListeners(EventType.POST_INSERT, listener);
        registry.appendListeners(EventType.POST_UPDATE, listener);
        registry.appendListeners(EventType.POST_DELETE, listener);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
                              SessionFactoryServiceRegistry serviceRegistry) {}
}

package io.github.ahnyeongjun.outbox.capture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import io.github.ahnyeongjun.outbox.annotation.OutboxDomain;
import io.github.ahnyeongjun.outbox.annotation.OutboxEvent;

class OutboxAspectTest {

    @AfterEach
    void tearDown() {
        OutboxContext.clear();
    }

    @Test
    void domainDisabled_suppressesAllMethods() {
        DisabledDomainService proxied = proxy(new DisabledDomainService());

        proxied.run();

        // suppress 후 컨텍스트가 정리되어 있어야 함
        assertThat(OutboxContext.get()).isNull();
    }

    @Test
    void domainEnabled_doesNotSuppress() {
        EnabledDomainService proxied = proxy(new EnabledDomainService());

        proxied.run();

        // enabled=true 분기는 컨텍스트를 만들지 않음
        assertThat(OutboxContext.get()).isNull();
    }

    @Test
    void eventDisabled_suppressesOnlyThatMethod() {
        SuppressOnMethod proxied = proxy(new SuppressOnMethod());

        // 일반 메서드에서 미리 컨텍스트를 만들어 두고
        OutboxContextData ctx = OutboxContext.getOrCreate();
        assertThat(ctx.isSuppressed()).isFalse();

        proxied.disabled();

        // disabled 메서드 종료 후 원래 suppress 상태(false) 로 복원되어야 함
        assertThat(OutboxContext.get().isSuppressed()).isFalse();
    }

    @Test
    void eventDisabled_keepsSuppressedIfAlreadyActive() {
        SuppressOnMethod proxied = proxy(new SuppressOnMethod());

        OutboxContextData ctx = OutboxContext.getOrCreate();
        ctx.suppress();

        proxied.disabled();

        // 이미 suppress 상태였다면 종료 후에도 그대로 유지
        assertThat(OutboxContext.get().isSuppressed()).isTrue();
    }

    @Test
    void eventType_setsCustomEventTypeForDuration() {
        CustomEventService proxied = proxy(new CustomEventService());

        proxied.bulk();

        // 메서드 종료 후에는 customEventType 이 해제되어야 함
        OutboxContextData ctx = OutboxContext.get();
        if (ctx != null) {
            assertThat(ctx.hasCustomEventType()).isFalse();
        }
    }

    @Test
    void eventType_visibleDuringExecution() {
        CapturingService bare = new CapturingService();
        CapturingService proxied = proxy(bare);

        proxied.tagged();

        assertThat(bare.observedEventType).isEqualTo("BULK_UPDATED");
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new OutboxAspect());
        return (T) factory.getProxy();
    }

    @OutboxDomain(enabled = false)
    static class DisabledDomainService {
        public void run() { }
    }

    @OutboxDomain
    static class EnabledDomainService {
        public void run() { }
    }

    static class SuppressOnMethod {
        @OutboxEvent(enabled = false)
        public void disabled() { }
    }

    static class CustomEventService {
        @OutboxEvent(eventType = "BULK_UPDATED")
        public void bulk() { }
    }

    static class CapturingService {
        String observedEventType;

        @OutboxEvent(eventType = "BULK_UPDATED")
        public void tagged() {
            OutboxContextData ctx = OutboxContext.get();
            observedEventType = ctx == null ? null : ctx.getCustomEventType();
        }
    }
}

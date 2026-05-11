package io.github.ahnyeongjun.outbox.capture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.ahnyeongjun.outbox.model.Outbox;

class OutboxContextDataTest {

    @Test
    void initialState_isClean() {
        OutboxContextData ctx = new OutboxContextData();
        assertThat(ctx.isSuppressed()).isFalse();
        assertThat(ctx.isSyncRegistered()).isFalse();
        assertThat(ctx.hasPendingEvents()).isFalse();
        assertThat(ctx.hasCustomEventType()).isFalse();
        assertThat(ctx.getCustomEventType()).isNull();
    }

    @Test
    void addEvent_appendsAndExposesPending() {
        OutboxContextData ctx = new OutboxContextData();
        ctx.addEvent(Outbox.builder().domain("USER").build());
        ctx.addEvent(Outbox.builder().domain("ORDER").build());

        assertThat(ctx.hasPendingEvents()).isTrue();
        assertThat(ctx.getPendingEvents()).hasSize(2);
    }

    @Test
    void suppress_andUnsuppress_toggleFlag() {
        OutboxContextData ctx = new OutboxContextData();
        ctx.suppress();
        assertThat(ctx.isSuppressed()).isTrue();
        ctx.unsuppress();
        assertThat(ctx.isSuppressed()).isFalse();
    }

    @Test
    void markSyncRegistered_setsFlag() {
        OutboxContextData ctx = new OutboxContextData();
        ctx.markSyncRegistered();
        assertThat(ctx.isSyncRegistered()).isTrue();
    }

    @Test
    void customEventType_emptyStringDoesNotCount() {
        OutboxContextData ctx = new OutboxContextData();
        ctx.setCustomEventType("");
        assertThat(ctx.hasCustomEventType()).isFalse();
    }

    @Test
    void customEventType_setAndClear() {
        OutboxContextData ctx = new OutboxContextData();
        ctx.setCustomEventType("BULK_UPDATED");
        assertThat(ctx.hasCustomEventType()).isTrue();
        assertThat(ctx.getCustomEventType()).isEqualTo("BULK_UPDATED");

        ctx.clearCustomEventType();
        assertThat(ctx.hasCustomEventType()).isFalse();
        assertThat(ctx.getCustomEventType()).isNull();
    }
}

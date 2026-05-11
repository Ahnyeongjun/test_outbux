package io.github.ahnyeongjun.outbox.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OutboxContextTest {

    @AfterEach
    void tearDown() {
        OutboxContext.clear();
    }

    @Test
    void getOrCreate_returnsSameInstanceForSameThread() {
        OutboxContextData a = OutboxContext.getOrCreate();
        OutboxContextData b = OutboxContext.getOrCreate();
        assertThat(a).isSameAs(b);
    }

    @Test
    void get_returnsNullIfNotInitialized() {
        assertThat(OutboxContext.get()).isNull();
    }

    @Test
    void clear_removesThreadLocal() {
        OutboxContext.getOrCreate();
        OutboxContext.clear();
        assertThat(OutboxContext.get()).isNull();
    }

    @Test
    void isSuppressed_falseWithoutContext() {
        assertThat(OutboxContext.isSuppressed()).isFalse();
    }

    @Test
    void runSuppressed_runnable_suppressesDuringExecutionAndClearsAfter() {
        AtomicBoolean seenSuppressed = new AtomicBoolean(false);

        OutboxContext.runSuppressed(() -> seenSuppressed.set(OutboxContext.isSuppressed()));

        assertThat(seenSuppressed.get()).isTrue();
        assertThat(OutboxContext.get()).isNull();
    }

    @Test
    void runSuppressed_callable_returnsValue() throws Exception {
        String result = OutboxContext.runSuppressed(() -> "ok");
        assertThat(result).isEqualTo("ok");
        assertThat(OutboxContext.get()).isNull();
    }

    @Test
    void runSuppressed_clearsContextEvenOnException() {
        assertThatThrownBy(() -> OutboxContext.runSuppressed((Runnable) () -> {
            throw new IllegalStateException("oops");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(OutboxContext.get()).isNull();
    }

    @Test
    void isolatedAcrossThreads() throws Exception {
        OutboxContext.getOrCreate().suppress();

        Thread t = new Thread(() -> {
            assertThat(OutboxContext.get()).isNull();
            assertThat(OutboxContext.isSuppressed()).isFalse();
        });
        t.start();
        t.join();

        assertThat(OutboxContext.isSuppressed()).isTrue();
    }
}

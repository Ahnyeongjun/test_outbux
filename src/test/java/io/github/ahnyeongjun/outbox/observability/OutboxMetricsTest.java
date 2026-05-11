package io.github.ahnyeongjun.outbox.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ahnyeongjun.outbox.spi.OutboxStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxMetricsTest {

    private SimpleMeterRegistry registry;
    private OutboxStore store;
    private OutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        store = mock(OutboxStore.class);
        when(store.countPending()).thenReturn(42L);
        metrics = new OutboxMetrics(registry, store);
    }

    @Test
    void recordCaptured_incrementsCounterWithDomainAndEventTypeTags() {
        metrics.recordCaptured("USER", "CREATED");
        metrics.recordCaptured("USER", "CREATED");
        metrics.recordCaptured("ORDER", "UPDATED");

        assertThat(registry.counter("outbox.events.captured", "domain", "USER", "event_type", "CREATED").count())
                .isEqualTo(2);
        assertThat(registry.counter("outbox.events.captured", "domain", "ORDER", "event_type", "UPDATED").count())
                .isEqualTo(1);
    }

    @Test
    void recordTrigger_classifiesTimeSizeBothReasons() {
        metrics.recordTrigger(true, false);   // time-only
        metrics.recordTrigger(false, true);   // size-only
        metrics.recordTrigger(true, true);    // both

        assertThat(registry.counter("outbox.batch.trigger", "reason", "time").count()).isEqualTo(1);
        assertThat(registry.counter("outbox.batch.trigger", "reason", "size").count()).isEqualTo(1);
        assertThat(registry.counter("outbox.batch.trigger", "reason", "both").count()).isEqualTo(1);
    }

    @Test
    void recordBatch_recordsTimerAndSizeSummary() {
        metrics.recordBatch(TimeUnit.MILLISECONDS.toNanos(50), 100);
        metrics.recordBatch(TimeUnit.MILLISECONDS.toNanos(120), 250);

        assertThat(registry.timer("outbox.batch.processed").count()).isEqualTo(2);
        assertThat(registry.summary("outbox.batch.size").count()).isEqualTo(2);
        assertThat(registry.summary("outbox.batch.size").mean()).isEqualTo(175.0);
    }

    @Test
    void recordBatch_zeroSize_doesNotRecordSizeSummary() {
        metrics.recordBatch(1000L, 0);

        assertThat(registry.timer("outbox.batch.processed").count()).isEqualTo(1);
        assertThat(registry.summary("outbox.batch.size").count()).isZero();
    }

    @Test
    void recordFileWrite_recordsTimerAndBytesSummary() {
        metrics.recordFileWrite(TimeUnit.MILLISECONDS.toNanos(10), 4096);
        metrics.recordFileWrite(TimeUnit.MILLISECONDS.toNanos(15), 8192);

        assertThat(registry.timer("outbox.file.write").count()).isEqualTo(2);
        assertThat(registry.summary("outbox.file.bytes").count()).isEqualTo(2);
        assertThat(registry.summary("outbox.file.bytes").totalAmount()).isEqualTo(12288.0);
    }

    @Test
    void recordFailed_incrementsByGivenCount() {
        metrics.recordFailed(5);
        metrics.recordFailed(3);

        assertThat(registry.counter("outbox.failed.total").count()).isEqualTo(8.0);
    }

    @Test
    void pendingGauge_queriesOutboxStoreOnRead() {
        // gauge 값은 lazy — 등록 시점이 아니라 read 시 store.countPending() 호출
        double pending = registry.find("outbox.pending").gauge().value();

        assertThat(pending).isEqualTo(42.0);
        assertThat(metrics.lastObservedPending()).isEqualTo(42);
    }
}

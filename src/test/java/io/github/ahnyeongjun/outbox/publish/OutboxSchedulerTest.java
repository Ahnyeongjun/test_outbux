package io.github.ahnyeongjun.outbox.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;

@ExtendWith(MockitoExtension.class)
class OutboxSchedulerTest {

    @Mock OutboxStore store;
    @Mock OutboxFileWriter fileWriter;

    OutboxProperties properties;
    OutboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.getBatch().setSize(10);
        properties.getBatch().setTimeTriggerMs(10 * 60 * 1000);
        scheduler = new OutboxScheduler(properties, store, fileWriter);
    }

    @Test
    void doesNothing_whenNeitherSizeNorTimeTriggered() {
        when(store.countPending()).thenReturn(5L);

        scheduler.processOutbox();

        verify(store, never()).processBatch(anyInt(), any());
    }

    @Test
    void sizeTrigger_delegatesToProcessBatchWithFileWriter() {
        when(store.countPending()).thenReturn(10L);
        when(store.processBatch(eq(10), any())).thenReturn(3);

        scheduler.processOutbox();

        verify(store).processBatch(eq(10), any());
    }

    @Test
    void timeTrigger_firesEvenIfPendingBelowSize() {
        properties.getBatch().setTimeTriggerMs(0L);
        when(store.countPending()).thenReturn(1L);
        when(store.processBatch(anyInt(), any())).thenReturn(1);

        scheduler.processOutbox();

        verify(store).processBatch(eq(10), any());
    }

    @Test
    void handlerArgPassedToProcessBatch_isFileWriterWrite() {
        when(store.countPending()).thenReturn(10L);
        when(store.processBatch(anyInt(), any())).thenReturn(2);

        scheduler.processOutbox();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<List<Outbox>>> handlerCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(store).processBatch(anyInt(), handlerCaptor.capture());

        List<Outbox> dummyBatch = List.of(
                Outbox.builder().id(1L).seq(1L).domain("USER")
                        .eventType("CREATED").source("INTERNAL").payload("{}").build()
        );
        handlerCaptor.getValue().accept(dummyBatch);
        verify(fileWriter).write(dummyBatch);
    }

    @Test
    void cleanUpSent_delegatesToStore() {
        scheduler.cleanUpSent();
        verify(store).deleteOldSent();
    }

    @Test
    void processBatch_zeroResult_doesNotThrow() {
        when(store.countPending()).thenReturn(10L);
        when(store.processBatch(anyInt(), any())).thenReturn(0);

        assertThat(scheduler).isNotNull();
        scheduler.processOutbox();
        verify(store).processBatch(anyInt(), any());
    }
}

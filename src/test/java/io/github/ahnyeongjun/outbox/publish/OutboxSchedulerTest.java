package io.github.ahnyeongjun.outbox.publish;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        properties.getBatch().setTimeTriggerMs(10 * 60 * 1000);   // 10분: 시간 트리거는 안 걸림
        scheduler = new OutboxScheduler(properties, store, fileWriter);
    }

    @Test
    void doesNothing_whenNeitherSizeNorTimeTriggered() {
        when(store.countPending()).thenReturn(5L);

        scheduler.processOutbox();

        verify(store, never()).findPendingWithLock(anyInt());
        verify(fileWriter, never()).write(anyList());
    }

    @Test
    void sizeTrigger_picksAndMarksSent() {
        when(store.countPending()).thenReturn(10L);
        List<Outbox> batch = List.of(outbox(1L));
        when(store.findPendingWithLock(10)).thenReturn(batch);

        scheduler.processOutbox();

        verify(fileWriter).write(batch);
        verify(store).markSent(batch);
    }

    @Test
    void emptyBatch_noWriteNoMark() {
        when(store.countPending()).thenReturn(10L);
        when(store.findPendingWithLock(anyInt())).thenReturn(List.of());

        scheduler.processOutbox();

        verify(fileWriter, never()).write(anyList());
        verify(store, never()).markSent(anyList());
    }

    @Test
    void fileWriteFailure_marksEachRowFailed() {
        when(store.countPending()).thenReturn(10L);
        List<Outbox> batch = List.of(outbox(1L), outbox(2L));
        when(store.findPendingWithLock(anyInt())).thenReturn(batch);
        when(fileWriter.write(batch)).thenThrow(new RuntimeException("disk full"));

        scheduler.processOutbox();

        verify(store, times(1)).markFailed(1L);
        verify(store, times(1)).markFailed(2L);
        verify(store, never()).markSent(any());
    }

    @Test
    void cleanUpSent_delegatesToStore() {
        scheduler.cleanUpSent();
        verify(store).deleteOldSent();
    }

    @Test
    void timeTrigger_firesEvenIfPendingBelowSize() throws Exception {
        properties.getBatch().setTimeTriggerMs(0L); // 즉시 시간 트리거 충족
        when(store.countPending()).thenReturn(1L);
        when(store.findPendingWithLock(anyInt())).thenReturn(List.of(outbox(99L)));

        scheduler.processOutbox();

        verify(fileWriter).write(anyList());
    }

    private Outbox outbox(long id) {
        return Outbox.builder().id(id).seq(id).domain("USER")
                .eventType("CREATED").source("INTERNAL").payload("{}").build();
    }
}

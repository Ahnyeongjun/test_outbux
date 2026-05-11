package io.github.ahnyeongjun.outbox.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.ahnyeongjun.outbox.config.OutboxProperties;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.spi.OutboxConverter;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;

@ExtendWith(MockitoExtension.class)
class OutboxEventFlusherTest {

    @Mock OutboxStore store;
    @Mock OutboxConverter defaultConverter;
    @Mock OutboxConverter orderConverter;

    OutboxProperties properties;
    OutboxEventFlusher flusher;

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.setTables(Set.of("user", "order"));

        flusher = new OutboxEventFlusher(
                store, properties,
                Map.of("defaultOutboxConverter", defaultConverter,
                       "orderOutboxConverter", orderConverter));
    }

    @AfterEach
    void tearDown() {
        OutboxContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void capture_ignoresTablesNotInWhitelist() {
        flusher.capture("audit_log", new Object(), "CREATED");

        verify(defaultConverter, never()).convert(any(), any(), any());
        verify(store, never()).saveAll(anyList());
    }

    @Test
    void capture_ignoresWhenSuppressed() {
        OutboxContext.getOrCreate().suppress();

        flusher.capture("user", new Object(), "UPDATED");

        verify(defaultConverter, never()).convert(any(), any(), any());
    }

    @Test
    void capture_normalizesSchemaPrefixAndCase() {
        when(defaultConverter.convert(any(), any(), any()))
                .thenReturn(Outbox.builder().domain("USER").build());

        flusher.capture("public.USER", new Object(), "UPDATED");

        verify(defaultConverter).convert(any(), any(), any());
    }

    @Test
    void capture_picksDomainSpecificConverter() {
        when(orderConverter.convert(any(), any(), any()))
                .thenReturn(Outbox.builder().domain("ORDER").build());

        flusher.capture("order", new Object(), "CREATED");

        verify(orderConverter).convert(any(), any(), any());
        verify(defaultConverter, never()).convert(any(), any(), any());
    }

    @Test
    void capture_usesCustomEventTypeIfPresent() {
        OutboxContext.getOrCreate().setCustomEventType("BULK_UPDATED");
        when(defaultConverter.convert(any(), any(), any()))
                .thenReturn(Outbox.builder().domain("USER").build());

        flusher.capture("user", new Object(), "UPDATED");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(defaultConverter).convert(any(), any(), eventTypeCaptor.capture());
        assertThat(eventTypeCaptor.getValue()).isEqualTo("BULK_UPDATED");
    }

    @Test
    void capture_skipsWhenNoConverterAvailable() {
        OutboxEventFlusher empty = new OutboxEventFlusher(store, properties, Map.of());

        empty.capture("user", new Object(), "CREATED");

        verify(store, never()).saveAll(anyList());
    }

    @Nested
    class WithoutTransaction {
        @Test
        void flushesImmediately() {
            when(defaultConverter.convert(any(), any(), any()))
                    .thenReturn(Outbox.builder().domain("USER").build());

            flusher.capture("user", new Object(), "CREATED");

            verify(store, times(1)).saveAll(anyList());
            assertThat(OutboxContext.get()).isNull();
        }
    }

    @Nested
    class WithinTransaction {
        @BeforeEach
        void start() {
            TransactionSynchronizationManager.initSynchronization();
        }

        @AfterEach
        void end() {
            TransactionSynchronizationManager.clear();
        }

        @Test
        void registersSynchronizationOnlyOnce_andFlushesOnBeforeCommit() {
            when(defaultConverter.convert(any(), any(), any()))
                    .thenReturn(Outbox.builder().domain("USER").build());

            flusher.capture("user", new Object(), "CREATED");
            flusher.capture("user", new Object(), "UPDATED");

            // 두 번 caputure 했지만 동기화는 한 번만 등록되어야 함
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);

            // 아직 flush 가 호출되면 안 됨
            verify(store, never()).saveAll(anyList());

            // beforeCommit 시점에 flush
            syncs.get(0).beforeCommit(false);

            ArgumentCaptor<List<Outbox>> captor = ArgumentCaptor.forClass(List.class);
            verify(store).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        void doesNotFlushOnReadOnly() {
            when(defaultConverter.convert(any(), any(), any()))
                    .thenReturn(Outbox.builder().domain("USER").build());

            flusher.capture("user", new Object(), "CREATED");
            TransactionSynchronizationManager.getSynchronizations().get(0).beforeCommit(true);

            verify(store, never()).saveAll(anyList());
        }

        @Test
        void clearsContextAfterCompletion() {
            when(defaultConverter.convert(any(), any(), any()))
                    .thenReturn(Outbox.builder().domain("USER").build());

            flusher.capture("user", new Object(), "CREATED");
            assertThat(OutboxContext.get()).isNotNull();

            TransactionSynchronizationManager.getSynchronizations().get(0)
                    .afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            assertThat(OutboxContext.get()).isNull();
        }
    }
}

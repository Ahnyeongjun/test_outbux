package io.github.ahnyeongjun.outbox.capture.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.ahnyeongjun.outbox.capture.OutboxEventFlusher;

class OutboxInterceptorTest {

    private OutboxEventFlusher flusher;
    private OutboxInterceptor interceptor;

    @BeforeEach
    void setUp() {
        flusher = mock(OutboxEventFlusher.class);
        interceptor = new OutboxInterceptor(flusher);
    }

    @Test
    void translatesSimpleMapperToTableName() throws Throwable {
        invoke("com.example.mapper.UserMapper.update", SqlCommandType.UPDATE, new Object());

        verify(flusher).capture(eq("user"), any(), eq("UPDATED"));
    }

    @Test
    void translatesCamelCaseMapperToSnakeCase() throws Throwable {
        invoke("com.example.mapper.OrderItemMapper.insert", SqlCommandType.INSERT, new Object());

        verify(flusher).capture(eq("order_item"), any(), eq("CREATED"));
    }

    @Test
    void mapsDeleteToDeleted() throws Throwable {
        invoke("com.example.mapper.OrderMapper.delete", SqlCommandType.DELETE, new Object());

        verify(flusher).capture(eq("order"), any(), eq("DELETED"));
    }

    @Test
    void skipsSelectStatements() throws Throwable {
        invoke("com.example.mapper.OrderMapper.findAll", SqlCommandType.SELECT, new Object());

        verify(flusher, never()).capture(any(), any(), any());
    }

    @Test
    void skipsUnknownStatements() throws Throwable {
        invoke("com.example.mapper.OrderMapper.unknown", SqlCommandType.UNKNOWN, new Object());

        verify(flusher, never()).capture(any(), any(), any());
    }

    @Test
    void passesParameterAsEntity() throws Throwable {
        Object param = new Object();
        invoke("com.example.mapper.UserMapper.insert", SqlCommandType.INSERT, param);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(flusher).capture(any(), captor.capture(), any());
        assertThat(captor.getValue()).isSameAs(param);
    }

    @Test
    void cachesTableNameResolution() throws Throwable {
        invoke("com.example.mapper.UserMapper.insert", SqlCommandType.INSERT, new Object());
        invoke("com.example.mapper.UserMapper.update", SqlCommandType.UPDATE, new Object());

        verify(flusher).capture(eq("user"), any(), eq("CREATED"));
        verify(flusher).capture(eq("user"), any(), eq("UPDATED"));
    }

    private void invoke(String statementId, SqlCommandType type, Object parameter) throws Throwable {
        MappedStatement ms = mock(MappedStatement.class);
        when(ms.getId()).thenReturn(statementId);
        when(ms.getSqlCommandType()).thenReturn(type);

        Invocation invocation = new Invocation(
                mock(Executor.class),
                Executor.class.getMethod("update", MappedStatement.class, Object.class),
                new Object[]{ms, parameter});

        interceptor.intercept(invocation);
    }
}

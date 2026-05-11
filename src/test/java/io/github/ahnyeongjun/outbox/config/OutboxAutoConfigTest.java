package io.github.ahnyeongjun.outbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ahnyeongjun.outbox.adapter.jdbc.JdbcOutboxStore;
import io.github.ahnyeongjun.outbox.adapter.jdbc.MySQLDialect;
import io.github.ahnyeongjun.outbox.adapter.jdbc.OutboxDialect;
import io.github.ahnyeongjun.outbox.adapter.jdbc.PostgreSQLDialect;
import io.github.ahnyeongjun.outbox.capture.DefaultOutboxConverter;
import io.github.ahnyeongjun.outbox.capture.OutboxAspect;
import io.github.ahnyeongjun.outbox.capture.OutboxEventFlusher;
import io.github.ahnyeongjun.outbox.model.Outbox;
import io.github.ahnyeongjun.outbox.spi.OutboxConverter;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;

/**
 * <p>주의: 이 테스트 클래스의 내부 클래스 중 {@code @Configuration} 또는 {@code @Component} 가
 * 붙은 것은 두지 말 것 — {@code OutboxAutoConfig} 의 {@code @ComponentScan("io.github.ahnyeongjun.outbox")}
 * 가 테스트 패키지까지 스캔하므로 자동 등록되어 다른 테스트(특히 {@code @SpringBootTest} 컨텍스트)를 오염시킨다.
 * 사용자 빈 주입은 {@code withBean(...)} 으로만 한다.
 */
class OutboxAutoConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    OutboxAutoConfig.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:autoconfig;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "outbox.batch.check-interval-ms=86400000"
            );

    @Test
    void defaultsToPostgreSQLDialect() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OutboxDialect.class);
            assertThat(ctx.getBean(OutboxDialect.class)).isInstanceOf(PostgreSQLDialect.class);
        });
    }

    @Test
    void mysqlDialectSelectedByProperty() {
        contextRunner
                .withPropertyValues("outbox.dialect=mysql")
                .run(ctx -> assertThat(ctx.getBean(OutboxDialect.class)).isInstanceOf(MySQLDialect.class));
    }

    @Test
    void mariadbDialectMapsToMySQL() {
        contextRunner
                .withPropertyValues("outbox.dialect=mariadb")
                .run(ctx -> assertThat(ctx.getBean(OutboxDialect.class)).isInstanceOf(MySQLDialect.class));
    }

    @Test
    void registersJdbcOutboxStoreAndFlusherByDefault() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OutboxStore.class);
            assertThat(ctx.getBean(OutboxStore.class)).isInstanceOf(JdbcOutboxStore.class);
            assertThat(ctx).hasSingleBean(OutboxEventFlusher.class);
            assertThat(ctx).hasSingleBean(OutboxAspect.class);
        });
    }

    @Test
    void registersDefaultConverter() {
        contextRunner.run(ctx -> {
            assertThat(ctx.containsBean("defaultOutboxConverter")).isTrue();
            assertThat(ctx.getBean("defaultOutboxConverter")).isInstanceOf(DefaultOutboxConverter.class);
        });
    }

    @Test
    void userProvidedDialectWins() {
        contextRunner
                .withBean(OutboxDialect.class, CustomDialect::new)
                .run(ctx -> {
                    assertThat(ctx.getBean(OutboxDialect.class)).isInstanceOf(CustomDialect.class);
                    assertThat(ctx.getBeansOfType(OutboxDialect.class)).hasSize(1);
                });
    }

    @Test
    void userProvidedStoreWins() {
        contextRunner
                .withBean(OutboxStore.class, CustomStore::new)
                .run(ctx -> {
                    assertThat(ctx.getBean(OutboxStore.class)).isInstanceOf(CustomStore.class);
                    assertThat(ctx.getBeansOfType(JdbcOutboxStore.class)).isEmpty();
                });
    }

    @Test
    void userProvidedDefaultConverterWins() {
        contextRunner
                .withBean("defaultOutboxConverter", OutboxConverter.class, CustomConverter::new)
                .run(ctx -> assertThat(ctx.getBean("defaultOutboxConverter"))
                        .isInstanceOf(CustomConverter.class));
    }

    static class CustomDialect implements OutboxDialect {
        @Override public String insertSql() { return ""; }
        @Override public String selectPendingWithLockSql() { return ""; }
        @Override public String deleteOldSentSql() { return ""; }
    }

    static class CustomStore implements OutboxStore {
        @Override public void saveAll(java.util.List<Outbox> events) {}
        @Override public long countPending() { return 0; }
        @Override public int processBatch(
                org.springframework.transaction.support.TransactionTemplate tx,
                int limit,
                java.util.function.Consumer<java.util.List<Outbox>> handler) { return 0; }
        @Override public void deleteOldSent() {}
    }

    static class CustomConverter implements OutboxConverter {
        @Override public Outbox convert(Object result, String domain, String eventType) {
            return Outbox.builder().build();
        }
    }
}

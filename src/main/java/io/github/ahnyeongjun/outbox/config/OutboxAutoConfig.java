package io.github.ahnyeongjun.outbox.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ahnyeongjun.outbox.store.JdbcOutboxStore;
import io.github.ahnyeongjun.outbox.store.MySQLDialect;
import io.github.ahnyeongjun.outbox.store.OutboxDialect;
import io.github.ahnyeongjun.outbox.store.PostgreSQLDialect;
import io.github.ahnyeongjun.outbox.capture.jpa.HibernateOutboxListener;
import io.github.ahnyeongjun.outbox.capture.jpa.OutboxHibernateIntegrator;
import io.github.ahnyeongjun.outbox.capture.mybatis.OutboxInterceptor;
import io.github.ahnyeongjun.outbox.capture.DefaultOutboxConverter;
import io.github.ahnyeongjun.outbox.capture.OutboxAspect;
import io.github.ahnyeongjun.outbox.capture.OutboxEventFlusher;
import io.github.ahnyeongjun.outbox.spi.OutboxConverter;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
@AutoConfigureBefore(name = "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration")
@ComponentScan(value = "io.github.ahnyeongjun.outbox",
               excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                                                      classes = OutboxAspect.class))
public class OutboxAutoConfig {

    @Bean
    public OutboxAspect outboxAspect() {
        return new OutboxAspect();
    }

    @Bean("defaultOutboxConverter")
    @ConditionalOnMissingBean(name = "defaultOutboxConverter")
    public OutboxConverter defaultOutboxConverter(ObjectMapper objectMapper) {
        return new DefaultOutboxConverter(objectMapper);
    }

    /**
     * SQL 방언 선택 — {@code outbox.dialect} 가 명시되면 그 값, 아니면 DataSource URL 에서 자동 감지.
     * 직접 {@link OutboxDialect} 빈을 등록하면 이 빈은 건너뜀.
     */
    @Bean
    @ConditionalOnMissingBean(OutboxDialect.class)
    public OutboxDialect outboxDialect(OutboxProperties properties, javax.sql.DataSource dataSource) {
        String dialect = properties.getDialect();
        if (dialect == null || dialect.isBlank()) {
            dialect = detectDialectFromDataSource(dataSource);
        }
        return switch (dialect.toLowerCase()) {
            case "mysql", "mariadb" -> new MySQLDialect();
            default -> new PostgreSQLDialect(properties.getSequenceName());
        };
    }

    /** DataSource JDBC URL 에서 방언 추론. 실패하면 postgresql 로 기본 처리. */
    private static String detectDialectFromDataSource(javax.sql.DataSource dataSource) {
        try (var conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            if (url != null) {
                String lower = url.toLowerCase();
                if (lower.startsWith("jdbc:mariadb:")) return "mariadb";
                if (lower.startsWith("jdbc:mysql:")) return "mysql";
                if (lower.startsWith("jdbc:postgresql:")) return "postgresql";
            }
        } catch (java.sql.SQLException ignored) {
            // 감지 실패 — 기본값으로 폴백
        }
        return "postgresql";
    }

    /**
     * 기본 저장소 — JDBC 기반. 직접 {@link OutboxStore} 빈을 등록하면 이 빈은 건너뜀.
     *
     * <p>JPA 프로젝트에서도 그대로 사용 가능 — {@code JdbcTemplate} 은 활성 트랜잭션의
     * Connection 을 공유하므로 {@code JpaTransactionManager} 가 관리하는 비즈니스 트랜잭션에
     * 자연스럽게 합류한다 (비즈니스 데이터와 outbox INSERT 가 한 commit 단위로 묶임).
     */
    @Bean
    @ConditionalOnMissingBean(OutboxStore.class)
    public OutboxStore outboxStore(NamedParameterJdbcTemplate jdbcTemplate,
                                    OutboxDialect dialect,
                                    PlatformTransactionManager transactionManager) {
        return new JdbcOutboxStore(jdbcTemplate, dialect, transactionManager);
    }

    @Bean
    public OutboxEventFlusher outboxEventFlusher(OutboxStore outboxStore,
                                                  OutboxProperties properties,
                                                  Map<String, OutboxConverter> converters) {
        return new OutboxEventFlusher(outboxStore, properties, converters);
    }

    /**
     * MyBatis 사용 환경의 <b>이벤트 캡처</b> 설정 — Executor.update() 인터셉트 기반.
     *
     * <p>{@code @ConditionalOnBean(name = "sqlSessionFactory")} 를 걸면 SqlSessionFactory 가
     * <em>먼저</em> 만들어진 후에야 본 인터셉터 빈이 생성되어, MybatisAutoConfiguration 이
     * 이미 만들어 둔 SqlSessionFactory 에 플러그인이 적용되지 않는다. 따라서 클래스 존재 여부만
     * 조건으로 두고 {@link AutoConfigureBefore} 로 MybatisAutoConfiguration 보다 먼저 처리되게 한다.
     */
    @Configuration
    @ConditionalOnClass(name = "org.apache.ibatis.plugin.Interceptor")
    static class MyBatisCaptureConfig {

        @Bean
        public OutboxInterceptor outboxInterceptor(OutboxEventFlusher flusher) {
            return new OutboxInterceptor(flusher);
        }
    }

    /** JPA/Hibernate 사용 환경의 <b>이벤트 캡처</b> 설정 — PostInsert/Update/Delete 이벤트 기반. */
    @Configuration
    @ConditionalOnClass(name = "org.hibernate.event.spi.PostInsertEventListener")
    @ConditionalOnBean(name = "entityManagerFactory")
    static class JpaCaptureConfig {

        @Bean
        public HibernateOutboxListener hibernateOutboxListener(OutboxEventFlusher flusher) {
            return new HibernateOutboxListener(flusher);
        }

        @Bean
        public HibernatePropertiesCustomizer outboxHibernateCustomizer(HibernateOutboxListener listener) {
            return props -> props.put(
                    "hibernate.integrator_provider",
                    (org.hibernate.jpa.boot.spi.IntegratorProvider)
                    () -> List.of(new OutboxHibernateIntegrator(listener))
            );
        }
    }
}

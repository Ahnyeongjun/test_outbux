package io.github.ahnyeongjun.outbox.config;

import java.util.List;
import java.util.Map;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ahnyeongjun.outbox.adapter.jdbc.JdbcOutboxStore;
import io.github.ahnyeongjun.outbox.adapter.jdbc.MySQLDialect;
import io.github.ahnyeongjun.outbox.adapter.jdbc.OutboxDialect;
import io.github.ahnyeongjun.outbox.adapter.jdbc.PostgreSQLDialect;
import io.github.ahnyeongjun.outbox.adapter.jpa.HibernateOutboxListener;
import io.github.ahnyeongjun.outbox.adapter.jpa.OutboxHibernateIntegrator;
import io.github.ahnyeongjun.outbox.adapter.mybatis.OutboxInterceptor;
import io.github.ahnyeongjun.outbox.capture.DefaultOutboxConverter;
import io.github.ahnyeongjun.outbox.capture.OutboxAspect;
import io.github.ahnyeongjun.outbox.capture.OutboxEventFlusher;
import io.github.ahnyeongjun.outbox.spi.OutboxConverter;
import io.github.ahnyeongjun.outbox.spi.OutboxStore;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
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

    /** outbox.dialect 값에 따라 방언 선택. 직접 OutboxDialect 빈을 등록하면 이 빈은 건너뜀 */
    @Bean
    @ConditionalOnMissingBean(OutboxDialect.class)
    public OutboxDialect outboxDialect(OutboxProperties properties) {
        return switch (properties.getDialect().toLowerCase()) {
            case "mysql", "mariadb" -> new MySQLDialect();
            default -> new PostgreSQLDialect(properties.getSequenceName());
        };
    }

    /** 기본 저장소. 직접 OutboxStore 빈을 등록하면 이 빈은 건너뜀 */
    @Bean
    @ConditionalOnMissingBean(OutboxStore.class)
    public OutboxStore outboxStore(NamedParameterJdbcTemplate jdbcTemplate, OutboxDialect dialect) {
        return new JdbcOutboxStore(jdbcTemplate, dialect);
    }

    @Bean
    public OutboxEventFlusher outboxEventFlusher(OutboxStore outboxStore,
                                                  OutboxProperties properties,
                                                  Map<String, OutboxConverter> converters) {
        return new OutboxEventFlusher(outboxStore, properties, converters);
    }

    /** MyBatis 사용 환경 — Executor.update() 인터셉트 기반 자동 이벤트 캡처 */
    @Configuration
    @ConditionalOnClass(name = "org.apache.ibatis.plugin.Interceptor")
    static class MyBatisOutboxConfig {

        @Bean
        @ConditionalOnBean(name = "sqlSessionFactory")
        public OutboxInterceptor outboxInterceptor(OutboxEventFlusher flusher) {
            return new OutboxInterceptor(flusher);
        }
    }

    /** JPA/Hibernate 사용 환경 — PostInsert/Update/Delete 이벤트 기반 자동 이벤트 캡처 */
    @Configuration
    @ConditionalOnClass(name = "org.hibernate.event.spi.PostInsertEventListener")
    @ConditionalOnBean(name = "entityManagerFactory")
    static class JpaOutboxConfig {

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

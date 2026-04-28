package io.github.ahnyeongjun.outbox.config;

import java.util.Map;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ahnyeongjun.outbox.aspect.OutboxAspect;
import io.github.ahnyeongjun.outbox.interceptor.OutboxInterceptor;
import io.github.ahnyeongjun.outbox.model.DefaultOutboxConverter;
import io.github.ahnyeongjun.outbox.model.OutboxConverter;
import io.github.ahnyeongjun.outbox.repository.OutboxRepository;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
@ComponentScan(value = "io.github.ahnyeongjun.outbox",
               excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                                                      classes = OutboxAspect.class))
@MapperScan("io.github.ahnyeongjun.outbox.mapper")
public class OutboxAutoConfig {

    /** @OutboxDomain(enabled=false) opt-out 처리 담당 */
    @Bean
    public OutboxAspect outboxAspect() {
        return new OutboxAspect();
    }

    /** 도메인 전용 컨버터가 없을 때 폴백. 민감 필드 자동 제외. */
    @Bean("defaultOutboxConverter")
    @ConditionalOnMissingBean(name = "defaultOutboxConverter")
    public OutboxConverter defaultOutboxConverter(ObjectMapper objectMapper) {
        return new DefaultOutboxConverter(objectMapper);
    }

    /** MyBatis Plugin 등록 — outbox.tables 목록 기반 자동 감지 */
    @Bean
    public OutboxInterceptor outboxInterceptor(OutboxProperties properties,
                                               Map<String, OutboxConverter> converters,
                                               ObjectProvider<OutboxRepository> outboxRepositoryProvider) {
        return new OutboxInterceptor(properties, converters, outboxRepositoryProvider);
    }
}

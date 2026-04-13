package io.github.ahnyeongjun.outbox.config;

import java.util.Map;

import org.mybatis.spring.annotation.MapperScan;
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

    /** @OutboxDomain(enabled=false) opt-out Ï≤òÎ¶¨ ?ÑÏö© */
    @Bean
    public OutboxAspect outboxAspect() {
        return new OutboxAspect();
    }

    /** ?ÑÎ©î???ÑÏö© Ïª®Î≤Ñ???ÜÏùÑ ???¥Î∞±. ÎØºÍ∞ê ?ÑÎìú ?êÎèô ?úÏô∏. */
    @Bean("defaultOutboxConverter")
    @ConditionalOnMissingBean(name = "defaultOutboxConverter")
    public OutboxConverter defaultOutboxConverter(ObjectMapper objectMapper) {
        return new DefaultOutboxConverter(objectMapper);
    }

    /** MyBatis Plugin ?±Î°ù ??outbox.tables Î™©Î°ù Í∏∞Î∞ò ?êÎèô Í∞êÏ? */
    @Bean
    public OutboxInterceptor outboxInterceptor(OutboxProperties properties,
                                               Map<String, OutboxConverter> converters,
                                               OutboxRepository outboxRepository) {
        return new OutboxInterceptor(properties, converters, outboxRepository);
    }
}

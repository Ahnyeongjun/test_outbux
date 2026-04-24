package io.github.ahnyeongjun.outbox.model;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기본 Outbox 컨버터.
 * 도메인 전용 컨버터({domain}OutboxConverter)가 없으면 이 구현체가 사용됨.
 *
 * <p>민감 필드(password, token 등)는 자동 제외.
 * 추가 제외가 필요하면 도메인 전용 컨버터를 등록할 것.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultOutboxConverter implements OutboxConverter {

    private static final String FILTER_ID = "outboxSensitiveFilter";

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "passwd", "secret", "token", "credential",
            "accessToken", "refreshToken", "apiKey", "privateKey"
    );

    private final ObjectMapper baseMapper;

    /** 민감 필드 제외 전용 ObjectMapper (전역 공유 오염 방지를 위해 복사본 사용) */
    private ObjectMapper safeMapper() {
        return baseMapper.copy()
                .addMixIn(Object.class, SensitiveFilterMixin.class)
                .setFilterProvider(new SimpleFilterProvider().addFilter(
                        FILTER_ID,
                        SimpleBeanPropertyFilter.serializeAllExcept(SENSITIVE_FIELDS)
                ));
    }

    @Override
    public Outbox convert(Object entity, String domain, String eventType) {
        String payload;
        try {
            payload = safeMapper().writeValueAsString(entity);
        } catch (Exception e) {
            log.warn("Outbox payload serialization failed for domain={}, fallback to empty: {}", domain, e.getMessage());
            payload = "{}";
        }
        return Outbox.builder()
                .domain(domain)
                .eventType(eventType)
                .source("INTERNAL")
                .payload(payload)
                .build();
    }

    @JsonFilter(FILTER_ID)
    private static class SensitiveFilterMixin {}
}

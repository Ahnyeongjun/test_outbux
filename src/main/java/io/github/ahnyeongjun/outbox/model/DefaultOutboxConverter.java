package io.github.ahnyeongjun.outbox.model;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ê¸°ë³¸ Outbox ì»¨ë²„??
 * ?„ë©”???„ìš© ì»¨ë²„??{domain}OutboxConverter)ê°€ ?†ìœ¼ë©???êµ¬í˜„???¬ìš©??
 *
 * <p>ë¯¼ê° ?„ë“œ(password, token ?????ë™ ?œì™¸.
 * ì¶”ê? ?œì™¸ê°€ ?„ìš”?˜ë©´ ?„ë©”???„ìš© ì»¨ë²„?°ë? ?±ë¡??ê²?
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

    /** ë¯¼ê° ?„ë“œ ?œì™¸ ?„ìš© ObjectMapper (ë¹?ê³µìœ  ?¤ì—¼ ë°©ì?ë¥??„í•´ ë³µì‚¬ë³??¬ìš©) */
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

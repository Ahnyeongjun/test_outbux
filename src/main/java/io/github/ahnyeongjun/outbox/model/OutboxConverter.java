package io.github.ahnyeongjun.outbox.model;

/**
 * 도메인별 Outbox 변환 인터페이스.
 *
 * <p>Bean 이름 규칙: {@code {domain소문자}OutboxConverter}
 * <pre>
 * domain = "ORDER"  → bean name = "orderOutboxConverter"
 * </pre>
 */
public interface OutboxConverter {
    /**
     * @param result    서비스 메서드 반환값
     * @param domain    도메인 식별자
     * @param eventType 자동 추론된 이벤트 타입 (CREATED / UPDATED / DELETED / CHANGED)
     */
    Outbox convert(Object result, String domain, String eventType);
}

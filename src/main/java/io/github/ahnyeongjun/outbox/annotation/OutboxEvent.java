package io.github.ahnyeongjun.outbox.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 레벨 Outbox 제어.
 * {@code @OutboxDomain} 서비스에서 특정 메서드만 제외하거나 이벤트 타입을 직접 지정할 때 사용.
 *
 * <pre>
 * // 제외
 * {@literal @}OutboxEvent(enabled = false)
 * public void internalSync(...) { ... }
 *
 * // 이벤트 타입 직접 지정
 * {@literal @}OutboxEvent(eventType = "BULK_UPDATED")
 * public void bulkUpdate(...) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OutboxEvent {
    boolean enabled() default true;
    String eventType() default "";
}

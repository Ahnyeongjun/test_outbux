package io.github.ahnyeongjun.outbox.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ë©”ì„œ???ˆë²¨ Outbox ?œì–´.
 * {@code @OutboxDomain} ?œë¹„?¤ì—???¹ì • ë©”ì„œ?œë§Œ ?œì™¸?˜ê±°???´ë²¤???€?…ì„ ì§ì ‘ ì§€?•í•  ???¬ìš©.
 *
 * <pre>
 * // ?œì™¸
 * {@literal @}OutboxEvent(enabled = false)
 * public void internalSync(...) { ... }
 *
 * // ?´ë²¤???€??ì§ì ‘ ì§€?? * {@literal @}OutboxEvent(eventType = "BULK_UPDATED")
 * public void bulkUpdate(...) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OutboxEvent {
    boolean enabled() default true;
    String eventType() default "";
}

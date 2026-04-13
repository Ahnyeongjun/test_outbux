package io.github.ahnyeongjun.outbox.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ?œë¹„???´ë˜?¤ì— ë¶™ì—¬ Outbox ?™ì‘??ëª…ì‹œ?ìœ¼ë¡??œì–´.
 * auto-detect ê°€ ì¼œì ¸ ?ˆìœ¼ë©??´ë…¸?Œì´???†ì´???ë™ ê°ì???
 *
 * <pre>
 * // auto-detect ë¡?ê°ì??˜ì?ë§??„ë©”?¸ëª…??ì§ì ‘ ì§€?•í•˜ê³??¶ì„ ?? * {@literal @}OutboxDomain("USER_MGMT")
 * {@literal @}Service
 * public class McUserService { ... }
 *
 * // auto-detect ?¨í„´??ê±¸ë ¤?????œë¹„?¤ëŠ” ?œì™¸
 * {@literal @}OutboxDomain(enabled = false)
 * {@literal @}Service
 * public class McInternalService { ... }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OutboxDomain {
    /** ?„ë©”?¸ëª… ì§ì ‘ ì§€?? ë¹„ì›Œ?ë©´ ?´ë˜?¤ëª…?¼ë¡œ ?ë™ ì¶”ë¡ . */
    String value() default "";
    /** false ?´ë©´ auto-detect ?¨í„´??ê±¸ë ¤?????œë¹„?¤ëŠ” ?œì™¸. */
    boolean enabled() default true;
}

package io.github.ahnyeongjun.outbox.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 서비스 클래스에 붙여 Outbox 동작을 명시적으로 제어하는 어노테이션.
 * auto-detect 가 켜져 있으면 어노테이션 없이도 자동 감지됨.
 *
 * <pre>
 * // auto-detect 로 감지되지 않을 때 도메인명을 직접 지정하고 싶을 때
 * {@literal @}OutboxDomain("USER_MGMT")
 * {@literal @}Service
 * public class McUserService { ... }
 *
 * // auto-detect 패턴에 걸려도 제외할 서비스는 비활성화
 * {@literal @}OutboxDomain(enabled = false)
 * {@literal @}Service
 * public class McInternalService { ... }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OutboxDomain {
    /** 도메인명 직접 지정. 비워두면 테이블명으로 자동 추론. */
    String value() default "";
    /** false 이면 auto-detect 패턴에 걸려도 이 서비스는 제외. */
    boolean enabled() default true;
}

package io.github.ahnyeongjun.outbox.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import io.github.ahnyeongjun.outbox.annotation.OutboxDomain;
import io.github.ahnyeongjun.outbox.annotation.OutboxEvent;
import io.github.ahnyeongjun.outbox.context.OutboxContext;
import io.github.ahnyeongjun.outbox.context.OutboxContextData;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code @OutboxDomain(enabled = false)} 가 붙은 서비스 클래스의 모든 메서드 호출에서
 * Outbox 이벤트 캡처를 차단(suppress)한다.
 *
 * <p>테이블 기반 자동 감지는 {@link io.github.ahnyeongjun.outbox.interceptor.OutboxInterceptor}가 담당하며,
 * Aspect 는 명시적 제외(opt-out) 처리를 수행한다.
 */
@Slf4j
@Aspect
public class OutboxAspect {

    @Around("@within(outboxDomain) && execution(* *..service.*Service.*(..))")
    public Object suppressIfDisabled(ProceedingJoinPoint pjp, OutboxDomain outboxDomain) throws Throwable {
        if (!outboxDomain.enabled()) {
            log.debug("Outbox suppressed by @OutboxDomain(enabled=false): {}",
                    pjp.getTarget().getClass().getSimpleName());
            OutboxContext.getOrCreate().suppress();
            try {
                return pjp.proceed();
            } finally {
                OutboxContext.clear();
            }
        }
        return pjp.proceed();
    }

    /**
     * 메서드 레벨 @OutboxEvent 처리.
     * enabled=false  → 해당 메서드 실행 중만 suppress (다른 메서드의 이벤트는 유지)
     * eventType      → 인터셉터의 SQL 타입 자동 추론 대신 지정값 사용
     */
    @Around("@annotation(outboxEvent) && execution(* *..service.*Service.*(..))")
    public Object handleOutboxEvent(ProceedingJoinPoint pjp, OutboxEvent outboxEvent) throws Throwable {
        if (!outboxEvent.enabled()) {
            log.debug("Outbox suppressed by @OutboxEvent(enabled=false): {}.{}",
                    pjp.getTarget().getClass().getSimpleName(), pjp.getSignature().getName());
            OutboxContextData ctx = OutboxContext.getOrCreate();
            boolean wasSuppressed = ctx.isSuppressed();
            ctx.suppress();
            try {
                return pjp.proceed();
            } finally {
                if (!wasSuppressed) ctx.unsuppress();
            }
        }

        if (!outboxEvent.eventType().isEmpty()) {
            OutboxContextData ctx = OutboxContext.getOrCreate();
            ctx.setCustomEventType(outboxEvent.eventType());
            try {
                return pjp.proceed();
            } finally {
                ctx.clearCustomEventType();
            }
        }

        return pjp.proceed();
    }
}

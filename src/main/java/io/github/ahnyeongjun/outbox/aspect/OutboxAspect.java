package io.github.ahnyeongjun.outbox.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import io.github.ahnyeongjun.outbox.annotation.OutboxDomain;
import io.github.ahnyeongjun.outbox.context.OutboxContext;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code @OutboxDomain(enabled = false)} ê°€ ë¶™ì? ?œë¹„???´ë˜?¤ì˜ ëª¨ë“  ë©”ì„œ???¸ì¶œ?ì„œ
 * Outbox ?´ë²¤??ìº¡ì²˜ë¥?ì°¨ë‹¨(suppress)?œë‹¤.
 *
 * <p>?Œì´ë¸?ê¸°ë°˜ ?ë™ ê°ì???{@link io.github.ahnyeongjun.outbox.interceptor.OutboxInterceptor}ê°€ ?´ë‹¹?˜ë©°,
 * Aspect ??ëª…ì‹œ???œì™¸(opt-out) ì²˜ë¦¬ë§??˜í–‰?œë‹¤.
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
}

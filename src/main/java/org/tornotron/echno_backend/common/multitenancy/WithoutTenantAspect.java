package org.tornotron.echno_backend.common.multitenancy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Puts the {@link WithoutTenant} declaration on the thread for the duration of the annotated
 * method and restores whatever was there before.
 *
 * <p>The order matters and is not cosmetic. {@link HibernateFilterConfig} sits at
 * {@link Ordered#LOWEST_PRECEDENCE} and reads the tenant scope on entry, so an aspect that
 * declared the scope at the same order might run after it and the declaration would arrive too
 * late to be seen. That is exactly the ordering accident #508 was, one aspect over. Ordering this
 * ahead of the filter aspect makes the declaration visible to it.
 *
 * <p>Restoring rather than clearing keeps the annotation safe on a method that is also called
 * from inside a request or a tenant-scoped job: the caller's scope comes back on the way out.
 */
@Aspect
@Component
@Order(WithoutTenantAspect.ORDER)
public class WithoutTenantAspect {

    /**
     * Ahead of {@link HibernateFilterConfig}, so the declaration is in place before the filter
     * aspect decides what to do about the transaction.
     */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Around("@annotation(withoutTenant)")
    public Object declareUnscoped(ProceedingJoinPoint joinPoint, WithoutTenant withoutTenant) throws Throwable {
        String previousReason = TenantContext.getUnscopedReason();
        TenantContext.declareUnscoped(withoutTenant.value());
        try {
            return joinPoint.proceed();
        } finally {
            if (previousReason == null) {
                TenantContext.clearUnscoped();
            } else {
                TenantContext.declareUnscoped(previousReason);
            }
        }
    }
}

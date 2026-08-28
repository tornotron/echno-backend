package org.tornotron.echno_backend.common.multitenancy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Turns tenant isolation off for the duration of a {@code @BypassTenantFilter} method and
 * restores the previous state on the way out.
 *
 * <p>Ordered ahead of {@link HibernateFilterConfig} for the same reason
 * {@link WithoutTenantAspect} is: that aspect reads the tenant scope on entry, so a bypass set
 * at the same order might be applied after it had already decided to enable the {@code orgFilter}
 * and would then have no effect on the transaction it was meant to open up. Both aspects were
 * previously unordered, which left that to chance.
 */
@Aspect
@Component
@Order(WithoutTenantAspect.ORDER)
public class TenantFilterBypassAspect {

    @Around("@annotation(bypassTenantFilter)")
    public Object bypassTenantFilter(ProceedingJoinPoint joinPoint, BypassTenantFilter bypassTenantFilter) throws Throwable {
        boolean wasBypassed = TenantContext.isBypassed();
        try {
            TenantContext.setBypass(true);
            return joinPoint.proceed();
        } finally {
            TenantContext.setBypass(wasBypassed);
        }
    }
}

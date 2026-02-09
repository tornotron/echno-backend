package org.tornotron.echno_backend.common.multitenancy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
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

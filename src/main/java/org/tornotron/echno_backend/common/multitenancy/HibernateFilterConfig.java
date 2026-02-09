package org.tornotron.echno_backend.common.multitenancy;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class HibernateFilterConfig {

    private final EntityManager entityManager;

    @Around("execution(* org.springframework.data.jpa.repository.JpaRepository+.*(..))")
    public Object enableOrgFilter(ProceedingJoinPoint joinPoint) throws Throwable {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId != null && !TenantContext.isBypassed()) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("orgFilter").setParameter("organizationId", orgId);
        }
        return joinPoint.proceed();
    }
}

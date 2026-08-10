package org.tornotron.echno_backend.common.multitenancy;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
public class HibernateFilterConfig {

    private final EntityManager entityManager;

    @Around("within(org.tornotron.echno_backend..*) && @annotation(transactional)")
    public Object enableOrgFilter(ProceedingJoinPoint joinPoint, Transactional transactional) throws Throwable {
        Long orgId = TenantContext.getCurrentOrgId();
        log.debug("[HibernateFilter] Aspect triggered for {}, orgId={}, bypassed={}",
                joinPoint.getSignature().toShortString(), orgId, TenantContext.isBypassed());
        if (orgId != null && !TenantContext.isBypassed()) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("orgFilter").setParameter("organizationId", orgId);
            log.debug("[HibernateFilter] orgFilter ENABLED for organization {}", orgId);
        } else {
            log.debug("[HibernateFilter] orgFilter NOT enabled (orgId={}, bypassed={})", orgId, TenantContext.isBypassed());
        }
        return joinPoint.proceed();
    }
}

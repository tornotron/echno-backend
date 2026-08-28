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

/**
 * Enables the Hibernate {@code orgFilter} on the session for the duration of a
 * {@code @Transactional} method, so queries in that transaction see only the current
 * organization's rows.
 *
 * <p>Three states arrive here and each is now answered explicitly:
 *
 * <ul>
 *   <li>An organization id is in force, so the filter goes on. The ordinary case.
 *   <li>Tenant isolation is deliberately off, either a {@code @BypassTenantFilter} reading across
 *       organizations or a {@link WithoutTenant} method that belongs to none. The filter stays
 *       off because that is what was asked for.
 *   <li>Nothing at all was declared. Until #507 this fell in with the case above and the
 *       transaction ran unfiltered, recorded only in a debug line no deployed environment has on.
 *       It now goes to {@link UnscopedAccessGuard}.
 * </ul>
 *
 * <p>This aspect reads the scope on entry, which is why anything that establishes one has to be
 * in place before the aspect runs rather than inside the method body. Setting it in the body is
 * one step too late and leaves the transaction unfiltered, which is what #508 was.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
public class HibernateFilterConfig {

    private final EntityManager entityManager;
    private final UnscopedAccessGuard unscopedAccessGuard;

    @Around("within(org.tornotron.echno_backend..*) && @annotation(transactional)")
    public Object enableOrgFilter(ProceedingJoinPoint joinPoint, Transactional transactional) throws Throwable {
        Long orgId = TenantContext.getCurrentOrgId();
        log.debug("[HibernateFilter] Aspect triggered for {}, orgId={}, bypassed={}, unscoped={}",
                joinPoint.getSignature().toShortString(), orgId, TenantContext.isBypassed(),
                TenantContext.getUnscopedReason());

        if (orgId != null && !TenantContext.isBypassed()) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("orgFilter").setParameter("organizationId", orgId);
            log.debug("[HibernateFilter] orgFilter ENABLED for organization {}", orgId);
        } else if (TenantContext.isScopeDeclared()) {
            log.debug("[HibernateFilter] orgFilter NOT enabled, tenant scope declared (orgId={}, "
                            + "bypassed={}, unscoped={})",
                    orgId, TenantContext.isBypassed(), TenantContext.getUnscopedReason());
        } else {
            unscopedAccessGuard.onUnscopedTransaction(joinPoint.getSignature().toShortString());
        }

        return joinPoint.proceed();
    }
}

package org.tornotron.echno_backend.common.multitenancy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.UnscopedTenantAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the transaction-boundary aspect does with each of the four scope states. The one that
 * changed is the fourth: an undeclared scope used to fall in with a deliberate bypass and run the
 * transaction unfiltered, recorded only in a debug line no deployed environment turns on.
 */
class HibernateFilterConfigTest {

    private EntityManager entityManager;
    private Session session;
    private Filter filter;
    private ProceedingJoinPoint joinPoint;

    @BeforeEach
    void setUp() throws Throwable {
        filter = mock(Filter.class);
        when(filter.setParameter(any(), any())).thenReturn(filter);
        session = mock(Session.class);
        when(session.enableFilter("orgFilter")).thenReturn(filter);
        entityManager = mock(EntityManager.class);
        when(entityManager.unwrap(Session.class)).thenReturn(session);

        Signature signature = mock(Signature.class);
        when(signature.toShortString()).thenReturn("SomeService.doWork()");
        joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenReturn("done");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private HibernateFilterConfig aspectUnder(UnscopedAccessPolicy policy) {
        return new HibernateFilterConfig(entityManager, new UnscopedAccessGuard(
                new SimpleMeterRegistry(), policy.name(), policy.name(), 60));
    }

    @Test
    void enablesTheFilterForTheCurrentOrganization() throws Throwable {
        TenantContext.setCurrentOrgId(9L);

        Object result = aspectUnder(UnscopedAccessPolicy.DENY).enableOrgFilter(joinPoint, null);

        assertThat(result).isEqualTo("done");
        verify(session).enableFilter("orgFilter");
        verify(filter).setParameter("organizationId", 9L);
    }

    @Test
    void leavesTheFilterOffForADeliberateBypass() throws Throwable {
        TenantContext.setBypass(true);

        assertThatCode(() -> aspectUnder(UnscopedAccessPolicy.DENY).enableOrgFilter(joinPoint, null))
                .doesNotThrowAnyException();

        verify(session, never()).enableFilter(any());
    }

    @Test
    void leavesTheFilterOffForAnUnscopedDeclaration() throws Throwable {
        TenantContext.declareUnscoped("startup, before any organization exists");

        assertThatCode(() -> aspectUnder(UnscopedAccessPolicy.DENY).enableOrgFilter(joinPoint, null))
                .doesNotThrowAnyException();

        verify(session, never()).enableFilter(any());
    }

    @Test
    void refusesATransactionThatDeclaresNoScopeAtAllWhenTheBoundaryDenies() {
        assertThatExceptionOfType(UnscopedTenantAccessException.class)
                .isThrownBy(() -> aspectUnder(UnscopedAccessPolicy.DENY).enableOrgFilter(joinPoint, null))
                .withMessageContaining("SomeService.doWork()");
    }

    @Test
    void proceedsUnderTheShippedTransactionBoundaryDefault() throws Throwable {
        // WARN is what the application ships with for this boundary, because it fires on every
        // @Transactional method including the many that touch no tenant-scoped entity.
        Object result = aspectUnder(UnscopedAccessPolicy.WARN).enableOrgFilter(joinPoint, null);

        assertThat(result).isEqualTo("done");
        verify(session, never()).enableFilter(any());
    }
}

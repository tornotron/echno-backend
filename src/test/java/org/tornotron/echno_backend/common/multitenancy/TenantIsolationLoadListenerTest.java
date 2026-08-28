package org.tornotron.echno_backend.common.multitenancy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.event.spi.PostLoadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.TenantAccessDeniedException;
import org.tornotron.echno_backend.common.exception.UnscopedTenantAccessException;
import org.tornotron.echno_backend.organization.Organization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fail-closed load listener denies a tenant-scoped entity that belongs to a
 * different organization than the request, and also one with no organization at
 * all (a null org cannot be proven to belong to the caller, and letting it
 * through let any tenant read a null-org row by id).
 *
 * <p>Since #507 it also denies a load that declares no tenant scope at all. That case used
 * to be an early return on the same condition {@code HibernateFilterConfig} skips the
 * {@code orgFilter} on, so the two mechanisms failed together rather than covering each other.
 */
class TenantIsolationLoadListenerTest {

    private final TenantIsolationLoadListener listener =
            listenerUnder(UnscopedAccessPolicy.DENY);

    private static TenantIsolationLoadListener listenerUnder(UnscopedAccessPolicy policy) {
        return new TenantIsolationLoadListener(new UnscopedAccessGuard(
                new SimpleMeterRegistry(), policy.name(), policy.name(), 60));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private PostLoadEvent eventFor(Object entity) {
        PostLoadEvent event = mock(PostLoadEvent.class);
        when(event.getEntity()).thenReturn(entity);
        return event;
    }

    private TenantScopedEntity scoped(Long orgId) {
        Organization org = orgId == null ? null : new Organization();
        if (org != null) {
            org.setId(orgId);
        }
        return new TenantScopedEntity() {
            @Override
            public Organization getOrganization() {
                return org;
            }

            @Override
            public void setOrganization(Organization organization) {
            }
        };
    }

    @Test
    void deniesAForeignOrganization() {
        TenantContext.setCurrentOrgId(1L);
        assertThatExceptionOfType(TenantAccessDeniedException.class)
                .isThrownBy(() -> listener.onPostLoad(eventFor(scoped(2L))));
    }

    @Test
    void deniesANullOrganization() {
        TenantContext.setCurrentOrgId(1L);
        assertThatExceptionOfType(TenantAccessDeniedException.class)
                .isThrownBy(() -> listener.onPostLoad(eventFor(scoped(null))));
    }

    @Test
    void allowsTheCallersOwnOrganization() {
        TenantContext.setCurrentOrgId(1L);
        assertThatCode(() -> listener.onPostLoad(eventFor(scoped(1L)))).doesNotThrowAnyException();
    }

    @Test
    void allowsWhenBypassed() {
        TenantContext.setCurrentOrgId(1L);
        TenantContext.setBypass(true);
        assertThatCode(() -> listener.onPostLoad(eventFor(scoped(2L)))).doesNotThrowAnyException();
    }

    @Test
    void ignoresNonTenantScopedEntities() {
        TenantContext.setCurrentOrgId(1L);
        assertThatCode(() -> listener.onPostLoad(eventFor("not a tenant entity"))).doesNotThrowAnyException();
    }

    @Test
    void deniesALoadThatDeclaresNoScopeAtAll() {
        // No organization id, no bypass, no @WithoutTenant. This used to return early, which
        // meant the row was handed over with no check of any kind.
        assertThatExceptionOfType(UnscopedTenantAccessException.class)
                .isThrownBy(() -> listener.onPostLoad(eventFor(scoped(2L))));
    }

    @Test
    void allowsALoadThatDeclaresItselfUnscoped() {
        TenantContext.declareUnscoped("startup, before any organization exists");

        assertThatCode(() -> listener.onPostLoad(eventFor(scoped(2L)))).doesNotThrowAnyException();
    }

    @Test
    void allowsAnUndeclaredLoadWhileThePolicyOnlyObserves() {
        // The staged rollout: an environment can sit on WARN and collect the call sites before
        // the default refuses them.
        TenantIsolationLoadListener observing = listenerUnder(UnscopedAccessPolicy.WARN);

        assertThatCode(() -> observing.onPostLoad(eventFor(scoped(2L)))).doesNotThrowAnyException();
    }

    @Test
    void anUnscopedDeclarationDoesNotWeakenAnActiveTenant() {
        // @WithoutTenant only suppresses the missing-scope failure. Where an organization id is
        // present the cross-tenant check stays in force, which is what makes the annotation safe
        // on a method that is also called inside a request.
        TenantContext.setCurrentOrgId(1L);
        TenantContext.declareUnscoped("shared helper that usually has no tenant");

        assertThatExceptionOfType(TenantAccessDeniedException.class)
                .isThrownBy(() -> listener.onPostLoad(eventFor(scoped(2L))));
    }
}

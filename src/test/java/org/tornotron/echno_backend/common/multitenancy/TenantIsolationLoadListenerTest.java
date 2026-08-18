package org.tornotron.echno_backend.common.multitenancy;

import org.hibernate.event.spi.PostLoadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.TenantAccessDeniedException;
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
 */
class TenantIsolationLoadListenerTest {

    private final TenantIsolationLoadListener listener = new TenantIsolationLoadListener();

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
}

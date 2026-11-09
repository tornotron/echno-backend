package org.tornotron.echno_backend.modules.access.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;

/**
 * {@code @access} in a {@code @PreAuthorize} expression asks the access service for the current
 * request's subject, and refuses when there is no subject or the key is malformed.
 */
@SpringJUnitConfig(AccessGuardTest.Config.class)
@WithMockUser
class AccessGuardTest {

    private static final AccessSubject ALICE = new AccessSubject(7L, 1L, 11L);

    @Configuration
    @EnableMethodSecurity
    static class Config {

        @Bean
        CurrentAccessSubject currentAccessSubject() {
            return mock(CurrentAccessSubject.class);
        }

        @Bean
        AccessService accessService() {
            return mock(AccessService.class);
        }

        @Bean("access")
        AccessGuard access(CurrentAccessSubject current, AccessService service) {
            return new AccessGuard(current, service);
        }

        @Bean
        Guarded guarded() {
            return new Guarded();
        }
    }

    static class Guarded {

        @PreAuthorize("@access.can('wbs', 'edit')")
        public String editWbs() {
            return "edited";
        }

        @PreAuthorize("@access.can('Bad Area', 'edit')")
        public String malformedKey() {
            return "should not run";
        }

        @PreAuthorize("@access.isMember()")
        public String readNotices() {
            return "read";
        }

        @PreAuthorize("@access.canIn(#organizationId, 'org-admin', 'delete')")
        public String deleteOrganization(Long organizationId) {
            return "deleted";
        }
    }

    @Autowired
    private Guarded guarded;

    @Autowired
    private CurrentAccessSubject current;

    @Autowired
    private AccessService access;

    @Autowired
    private AccessGuard guard;

    @BeforeEach
    void subjectIsAlice() {
        reset(current, access);
        when(current.resolve()).thenReturn(Optional.of(ALICE));
    }

    @Test
    void canAllowsWhatTheServiceAllows() {
        when(access.check(ALICE, AccessPermission.of("wbs:edit"))).thenReturn(true);

        assertThat(guarded.editWbs()).isEqualTo("edited");
    }

    @Test
    void canRefusesWhatTheServiceRefuses() {
        when(access.check(ALICE, AccessPermission.of("wbs:edit"))).thenReturn(false);

        assertThatThrownBy(guarded::editWbs).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void isMemberFollowsTheService() {
        when(access.isActiveMember(ALICE)).thenReturn(true);
        assertThat(guarded.readNotices()).isEqualTo("read");

        when(access.isActiveMember(ALICE)).thenReturn(false);
        assertThatThrownBy(guarded::readNotices).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void everythingIsRefusedWithoutASubject() {
        when(current.resolve()).thenReturn(Optional.empty());
        when(access.check(ALICE, AccessPermission.of("wbs:edit"))).thenReturn(true);
        when(access.isActiveMember(ALICE)).thenReturn(true);

        assertThatThrownBy(guarded::editWbs).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(guarded::readNotices).isInstanceOf(AccessDeniedException.class);
    }

    /** Alice is a member of organization 7, the request's; Bob is a member of organization 8 only. */
    private static final AccessSubject BOB_IN_8 = new AccessSubject(8L, 2L, 12L);

    /** Resolves the caller as the given membership in each organization, from the request's organization at the time. */
    private void memberships(Long organization, AccessSubject subject) {
        when(current.resolve()).thenAnswer(call -> organization.equals(TenantContext.getCurrentOrgId())
                ? Optional.of(subject) : Optional.empty());
    }

    @Test
    void canInAnswersForAMemberOfTheOrganizationItNames() {
        TenantContext.setCurrentOrgId(7L);
        try {
            memberships(8L, BOB_IN_8);
            when(access.check(BOB_IN_8, AccessPermission.of("org-admin:delete"))).thenReturn(true);

            assertThat(guarded.deleteOrganization(8L)).isEqualTo("deleted");
            assertThat(TenantContext.getCurrentOrgId()).as("the request's organization is restored").isEqualTo(7L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void canInRefusesAMemberOfOnlyTheRequestsOrganization() {
        TenantContext.setCurrentOrgId(7L);
        try {
            memberships(7L, ALICE);
            when(access.check(ALICE, AccessPermission.of("org-admin:delete"))).thenReturn(true);

            assertThatThrownBy(() -> guarded.deleteOrganization(8L)).isInstanceOf(AccessDeniedException.class);
            assertThat(TenantContext.getCurrentOrgId()).isEqualTo(7L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void canInRefusesSomeoneWhoIsAMemberOfNeither() {
        TenantContext.setCurrentOrgId(7L);
        try {
            when(current.resolve()).thenReturn(Optional.empty());

            assertThatThrownBy(() -> guarded.deleteOrganization(8L)).isInstanceOf(AccessDeniedException.class);
            assertThat(guard.canIn(null, "org-admin", "delete")).isFalse();
            assertThat(guard.canIn(8L, "Org", "delete")).isFalse();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aMalformedKeyInAGuardRefusesAndIsNeverAFailure() {
        assertThatThrownBy(guarded::malformedKey).isInstanceOf(AccessDeniedException.class);
        verify(access, never()).check(any(), any());
    }

    @Test
    void aMalformedOrMissingKeyAnswersFalse() {
        assertThat(guard.can("Finance", "read")).isFalse();
        assertThat(guard.can("finance", "read:all")).isFalse();
        assertThat(guard.can("", "")).isFalse();
        assertThat(guard.can(null, "read")).isFalse();
        assertThat(guard.can("finance", null)).isFalse();
    }
}

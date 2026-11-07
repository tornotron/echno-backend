package org.tornotron.echno_backend.modules.access.matrix.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.IdentityCapabilities;
import org.tornotron.echno_backend.common.identity.OrgRoleGroups;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.SignInMethod;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakTokenMapper;
import org.tornotron.echno_backend.common.identity.link.MembershipAuthorities;
import org.tornotron.echno_backend.employee.Employee;

/**
 * Audit G-05: Echno's change takes effect whatever the provider does, and the provider hears about it
 * only afterwards, through the queue. Nothing here calls the provider during the change.
 */
class OffboardingFollowsAtTheProviderIT extends RoleAssignmentITSupport {

    private Employee administrator() {
        Employee admin = employee("offboarding-admin");
        employeeService.assignOrgRoleAsSystem(admin.getId(), OrgRole.SYSTEM_ADMIN);
        flush();
        signInAs(admin, "system-admin");
        return admin;
    }

    private java.util.List<String> tasksAbout(Employee person) {
        flush();
        return jdbc.queryForList("SELECT kind FROM identity_task WHERE account = ? ORDER BY kind",
                String.class, person.getUser().getKeycloakId());
    }

    @Test
    void aStatusChangeEndsAccessBeforeTheTaskRuns() {
        administrator();
        Employee leaver = employee("offboarding-leaver");
        MembershipAuthorities membership = new MembershipAuthorities(jdbc, new KeycloakTokenMapper());
        Principal them = new Principal("keycloak", leaver.getUser().getKeycloakId(), null, SignInMethod.UNKNOWN);
        assertThat(membership.read(them).authorities()).isNotEmpty();

        employeeService.partialUpdateAnEmployee(Map.of("status", "resigned"), leaver.getId());
        flush();

        assertThat(membership.read(them).authorities()).isEmpty();
        assertThat(tasksAbout(leaver)).containsExactly("END_SESSIONS");
        verify(identityProvider, never()).endSessions(any());
    }

    @Test
    void deletingAnEmployeeAsksThatTheProviderFollow() {
        administrator();
        Employee leaver = employee("offboarding-deleted");

        employeeService.deleteAnEmployee(leaver.getId());

        assertThat(tasksAbout(leaver)).containsExactly("END_SESSIONS", "REMOVE_MEMBER");
        verify(identityProvider, never()).removeMember(any(), any());
    }

    @Test
    void aRoleChangeReachesTheProvidersCopyOnlyThroughTheQueue() {
        OrgRoleGroups roleGroups = mock(OrgRoleGroups.class);
        when(identityProvider.capabilities()).thenReturn(new IdentityCapabilities(Optional.empty(), Optional.of(roleGroups)));
        administrator();
        Employee target = employee("offboarding-role");

        employeeService.assignOrgRole(target.getId(), OrgRole.STORE_KEEPER);

        assertThat(tasksAbout(target)).containsExactly("ASSIGN_ROLE");
        verifyNoInteractions(roleGroups);
    }

    @Test
    void aProviderWithNoRoleCopyIsNotAskedToKeepOne() {
        administrator();
        Employee target = employee("offboarding-norole");

        employeeService.assignOrgRole(target.getId(), OrgRole.STORE_KEEPER);

        assertThat(tasksAbout(target)).isEmpty();
    }
}

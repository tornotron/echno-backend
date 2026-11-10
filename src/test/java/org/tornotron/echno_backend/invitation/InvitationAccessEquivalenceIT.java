package org.tornotron.echno_backend.invitation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.TeamAccess;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Moving invitations onto the access matrix changes nobody's access (design note 2.5): the system
 * admin and the HR admin still invite a person and keep the organization's invitations, through
 * team:invite across the organization, and no other starting role does.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 */
@Import(TeamAccess.class)
class InvitationAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> INVITERS = Set.of("system-admin", "hr-admin");
    private static final String GUARD = "@access.can('team', 'invite') and @teamAccess.acrossOrganization('invite')";

    @Autowired
    private AccessService access;

    @Autowired
    private TeamAccess team;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerAsksTheSameGuard() {
        assertThat(Arrays.stream(InvitationControllerWeb.class.getDeclaredMethods())
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .map(method -> method.getAnnotation(PreAuthorize.class).value()))
                .hasSize(5)
                .containsOnly(GUARD);
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("invite-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertInvitesExactlyWhenTheOldGuardDid(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartDoesNotInvite(String slug) {
        Employee person = employee("invite-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertInvitesExactlyWhenTheOldGuardDid(person, Set.of());
    }

    @Test
    void theBaselineAloneDoesNotInvite() {
        Employee person = employee("invite-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertInvitesExactlyWhenTheOldGuardDid(person, Set.of());
    }

    private void assertInvitesExactlyWhenTheOldGuardDid(Employee person, Set<String> oldRoles) {
        flush();
        signInAs(person);
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        boolean before = oldRoles.stream().anyMatch(INVITERS::contains);

        boolean after = access.check(subject, new AccessPermission("team", "invite"))
                && team.acrossOrganization("invite");
        assertThat(after).as("inviting for %s", oldRoles).isEqualTo(before);
    }
}

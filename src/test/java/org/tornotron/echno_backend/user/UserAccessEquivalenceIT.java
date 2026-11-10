package org.tornotron.echno_backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
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
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.TeamAccess;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Moving the user account handlers, on the tenant API and its {@code /web} twin, onto the access matrix
 * changes nobody's access at the guard (design note 2.5).
 *
 * <p>Before the move the system admin and HR admin listed the accounts, and the system admin alone
 * changed, deleted or batch-changed another person's account or listed their organizations, where the
 * person themselves also passed on their own. After it, the guard asks the area across the organization
 * through {@link TeamAccess}, and the self branch is the same {@code isSelfUser} as before, so it is
 * taken as given here. The handlers about the signed-in caller alone were and stay open.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 */
@Import(TeamAccess.class)
class UserAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Each handler's old rule: whether it was open, whether the user passed on their own account, and which roles passed on anyone's. */
    private record Old(boolean open, boolean self, Set<String> roles) {}

    private static final Old OPEN = new Old(true, true, Set.of());
    private static final Old LIST = new Old(false, false, Set.of("system-admin", "hr-admin"));
    private static final Old ADMIN = new Old(false, false, Set.of("system-admin"));
    private static final Old SELF_OR_ADMIN = new Old(false, true, Set.of("system-admin"));

    private static final Map<String, Old> OLD_GUARDS = Map.ofEntries(
            Map.entry("UserController#readAllUsers", LIST),
            Map.entry("UserController#readAllOrganizationsForCurrentUser", SELF_OR_ADMIN),
            Map.entry("UserController#readAnUser", OPEN),
            Map.entry("UserController#partialUpdateAUser", SELF_OR_ADMIN),
            Map.entry("UserController#batchUpdateUsers", ADMIN),
            Map.entry("UserController#deleteAnUser", ADMIN),
            Map.entry("UserControllerWeb#readAllUsers", LIST),
            Map.entry("UserControllerWeb#readAllOrganizationsForCurrentUser", SELF_OR_ADMIN),
            Map.entry("UserControllerWeb#getCurrentUser", OPEN),
            Map.entry("UserControllerWeb#getEmployeesForCurrentUser", OPEN),
            Map.entry("UserControllerWeb#partialUpdateAUser", SELF_OR_ADMIN),
            Map.entry("UserControllerWeb#batchUpdateUsers", ADMIN),
            Map.entry("UserControllerWeb#deleteAnUser", ADMIN));

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
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(UserGuardsTest.handlers()).extracting(UserGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("user-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardsGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesWhatTheBaselineDoes(String slug) {
        Employee person = employee("user-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("user-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        signInAs(person);
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : UserGuardsTest.handlers()) {
            String key = UserGuardsTest.key(handler);
            Old old = OLD_GUARDS.get(key);
            String expression = handler.getAnnotation(PreAuthorize.class).value();
            if (old.open()) {
                assertThat(expression).as(key).isEqualTo("isAuthenticated()");
                continue;
            }
            AccessPermission permission = permissionOf(expression, key);
            boolean held = old.roles().stream().anyMatch(oldRoles::contains);
            boolean afterOnAnyone = access.check(subject, permission) && team.acrossOrganization(permission.action());
            boolean afterOnOwn = expression.startsWith("@orgSecurity.isSelfUser(") || afterOnAnyone;

            assertThat(afterOnOwn).as("%s on their own account, for %s", key, oldRoles).isEqualTo(old.self() || held);
            assertThat(afterOnAnyone).as("%s on another account, for %s", key, oldRoles).isEqualTo(held);
        }
    }

    private static AccessPermission permissionOf(String expression, String key) {
        Set<AccessPermission> permissions = AccessGuardExpressions.parse(expression).permissions();
        assertThat(permissions).as(key).hasSize(1);
        return permissions.iterator().next();
    }
}

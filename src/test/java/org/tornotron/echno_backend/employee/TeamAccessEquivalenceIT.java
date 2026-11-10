package org.tornotron.echno_backend.employee;

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
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Moving employee records, on the tenant API and its {@code /web} twin, onto the access matrix
 * changes nobody's access (design note 2.5).
 *
 * <p>Before the move, four shapes of check decided it: any member; the system admin, HR admin or
 * project manager, on anyone's record and, where the guard said so, the employee on their own; the
 * system admin or HR admin, likewise; and nobody, for the closed create. After it, the guard asks the
 * area and {@link TeamAccess} asks whose record it is. Each handler is checked for a caller acting on
 * their own record and on a colleague's, through the real bean and the seeded starting roles.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 */
@Import(TeamAccess.class)
class TeamAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> DIRECTORY = Set.of("system-admin", "hr-admin", "project-manager");
    private static final Set<String> PERSONNEL = Set.of("system-admin", "hr-admin");

    /** Each handler's old rule: whether any member passed, whether the employee passed on their own record, and which roles passed on anyone's. */
    private record Old(boolean anyMember, boolean self, Set<String> roles) {}

    private static final Old NOBODY = new Old(false, false, Set.of());
    private static final Old ANY_MEMBER = new Old(true, true, Set.of());
    private static final Old READS_EVERYONE = new Old(false, false, DIRECTORY);
    private static final Old SELF_OR_READS_EVERYONE = new Old(false, true, DIRECTORY);
    private static final Old PERSONNEL_ONLY = new Old(false, false, PERSONNEL);
    private static final Old SELF_OR_PERSONNEL = new Old(false, true, PERSONNEL);

    private static final Map<String, Old> OLD_GUARDS = Map.ofEntries(
            Map.entry("EmployeeController#createEmployee", NOBODY),
            Map.entry("EmployeeController#readAllEmployees", READS_EVERYONE),
            Map.entry("EmployeeController#readAnEmployee", SELF_OR_READS_EVERYONE),
            Map.entry("EmployeeController#readEmployeesByOrganizationId", READS_EVERYONE),
            Map.entry("EmployeeController#partialUpdateAnEmployee", SELF_OR_PERSONNEL),
            Map.entry("EmployeeController#batchUpdateEmployees", PERSONNEL_ONLY),
            Map.entry("EmployeeController#deleteEmployee", PERSONNEL_ONLY),
            Map.entry("EmployeeControllerWeb#lookupEmployees", ANY_MEMBER),
            Map.entry("EmployeeControllerWeb#readAllEmployees", READS_EVERYONE),
            Map.entry("EmployeeControllerWeb#readAllEmployeesPaginated", READS_EVERYONE),
            Map.entry("EmployeeControllerWeb#readAnEmployee", SELF_OR_READS_EVERYONE),
            Map.entry("EmployeeControllerWeb#partialUpdateAnEmployee", SELF_OR_PERSONNEL),
            Map.entry("EmployeeControllerWeb#deleteEmployee", PERSONNEL_ONLY),
            Map.entry("EmployeeControllerWeb#assignManager", PERSONNEL_ONLY),
            Map.entry("EmployeeControllerWeb#removeManager", PERSONNEL_ONLY),
            Map.entry("EmployeeControllerWeb#getDirectSubordinates", SELF_OR_PERSONNEL),
            Map.entry("EmployeeControllerWeb#getAllTheManagers", READS_EVERYONE),
            Map.entry("EmployeeControllerWeb#getAllManagersForAnOrganization", READS_EVERYONE),
            Map.entry("EmployeeControllerWeb#assignOrgRole", PERSONNEL_ONLY),
            Map.entry("EmployeeControllerWeb#removeOrgRole", PERSONNEL_ONLY),
            Map.entry("EmployeeControllerWeb#getOrgRoles", ANY_MEMBER));

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
        assertThat(TeamGuardsTest.handlers()).extracting(TeamGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("team-eq-" + role.getGroupName());
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
        Employee person = employee("team-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("team-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        Employee colleague = employee("team-eq-colleague");
        flush();
        signInAs(person);
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : TeamGuardsTest.handlers()) {
            String key = TeamGuardsTest.key(handler);
            Old old = OLD_GUARDS.get(key);
            String expression = handler.getAnnotation(PreAuthorize.class).value();
            if (expression.equals(TeamGuardsTest.DENIED)) {
                assertThat(old).as(key).isEqualTo(NOBODY);
                continue;
            }
            AccessPermission permission = permissionOf(expression, key);
            boolean guard = access.check(subject, permission);
            boolean held = old.roles().stream().anyMatch(oldRoles::contains);

            boolean beforeOnOwn = old.anyMember() || old.self() || held;
            boolean beforeOnColleague = old.anyMember() || held;
            boolean afterOnOwn = guard && beyondTheGuard(expression, permission, person);
            boolean afterOnColleague = guard && beyondTheGuard(expression, permission, colleague);

            assertThat(afterOnOwn).as("%s on their own, for %s", key, oldRoles).isEqualTo(beforeOnOwn);
            assertThat(afterOnColleague).as("%s on a colleague, for %s", key, oldRoles)
                    .isEqualTo(beforeOnColleague);
        }
    }

    /** What the guard asks after the area, for a call about {@code concerned}'s record. */
    private boolean beyondTheGuard(String expression, AccessPermission permission, Employee concerned) {
        if (expression.contains("@teamAccess.allowsFor")) {
            return team.allowsFor(permission.action(), concerned.getId());
        }
        if (expression.contains("@teamAccess.acrossOrganization")) {
            return team.acrossOrganization(permission.action());
        }
        return true;
    }

    private static AccessPermission permissionOf(String expression, String key) {
        Set<AccessPermission> permissions = AccessGuardExpressions.parse(expression).permissions();
        assertThat(permissions).as(key).hasSize(1);
        return permissions.iterator().next();
    }
}

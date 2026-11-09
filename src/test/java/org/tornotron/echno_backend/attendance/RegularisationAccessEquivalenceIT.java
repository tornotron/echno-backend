package org.tornotron.echno_backend.attendance;

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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Moving attendance regularisation, on the tenant API and its {@code /web} twin, onto the access
 * matrix changes nobody's access (design note 2.5).
 *
 * <p>Before the move, three shapes of check decided it. Any member read a request by id. The
 * employee themselves, or a holder of a record-management role (system admin, HR admin, project
 * manager), raised a request on a day and read the month's calendar. Only those roles read the
 * register and the pending queue and decided a request.
 *
 * <p>After it, the guard asks the area and {@link RegularisationAccess} asks the rest: the
 * baseline holds read and create at own scope and the three roles hold read, create and edit
 * across the organization. Each handler is checked here for a caller acting on their own
 * attendance and on a colleague's, through the real bean and the seeded starting roles.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 */
@Import(RegularisationAccess.class)
class RegularisationAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** The record-management roles. */
    private static final Set<String> MANAGERS = Set.of("system-admin", "hr-admin", "project-manager");

    /** How a handler's access is settled after the move, beyond its guard. */
    private enum Shape {
        /** The guard alone. */
        GUARD,
        /** The guard, then the action on the attendance of the employee concerned. */
        ON_AN_EMPLOYEE,
        /** The guard, then the action across the whole register. */
        WHOLE_REGISTER
    }

    /**
     * Each handler's old rule, by controller and handler: whether any member passed, whether the
     * employee passed on their own attendance, and which roles passed on anyone's.
     */
    private record Old(Shape shape, boolean anyMember, boolean self, Set<String> roles) {}

    private static final Old ANY_MEMBER = new Old(Shape.GUARD, true, true, Set.of());
    private static final Old SELF_OR_MANAGER = new Old(Shape.ON_AN_EMPLOYEE, false, true, MANAGERS);
    private static final Old MANAGER_ON_ANYONE = new Old(Shape.ON_AN_EMPLOYEE, false, false, MANAGERS);
    private static final Old MANAGER_REGISTER = new Old(Shape.WHOLE_REGISTER, false, false, MANAGERS);

    private static final Map<String, Old> OLD_GUARDS = Map.ofEntries(
            Map.entry("AttendanceRegularizationController#submitRequest", SELF_OR_MANAGER),
            Map.entry("AttendanceRegularizationController#submitByDate", SELF_OR_MANAGER),
            Map.entry("AttendanceRegularizationController#calendar", SELF_OR_MANAGER),
            Map.entry("AttendanceRegularizationController#process", MANAGER_ON_ANYONE),
            Map.entry("AttendanceRegularizationController#getPending", MANAGER_REGISTER),
            Map.entry("AttendanceRegularizationController#getById", ANY_MEMBER),
            Map.entry("AttendanceRegularizationControllerWeb#submitRequest", SELF_OR_MANAGER),
            Map.entry("AttendanceRegularizationControllerWeb#submitByDate", SELF_OR_MANAGER),
            Map.entry("AttendanceRegularizationControllerWeb#calendar", SELF_OR_MANAGER),
            Map.entry("AttendanceRegularizationControllerWeb#process", MANAGER_ON_ANYONE),
            Map.entry("AttendanceRegularizationControllerWeb#getPending", MANAGER_REGISTER),
            Map.entry("AttendanceRegularizationControllerWeb#list", MANAGER_REGISTER),
            Map.entry("AttendanceRegularizationControllerWeb#getById", ANY_MEMBER));

    @Autowired
    private AccessService access;

    @Autowired
    private RegularisationAccess regularisations;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(RegularisationGuardsTest.handlers()).extracting(RegularisationGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("reg-eq-" + role.getGroupName());
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
        Employee person = employee("reg-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("reg-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        Employee colleague = employee("reg-eq-colleague");
        flush();
        signInAs(person);
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        boolean manager = oldRoles.stream().anyMatch(MANAGERS::contains);
        for (Method handler : RegularisationGuardsTest.handlers()) {
            String key = RegularisationGuardsTest.key(handler);
            Old old = OLD_GUARDS.get(key);
            AccessPermission permission = permissionOf(handler);
            boolean guard = access.check(subject, permission);

            boolean beforeOnOwn = old.anyMember() || old.self() || manager && !old.roles().isEmpty();
            boolean beforeOnColleague = old.anyMember() || manager && !old.roles().isEmpty();
            boolean afterOnOwn = guard && beyondTheGuard(old.shape(), permission, person);
            boolean afterOnColleague = guard && beyondTheGuard(old.shape(), permission, colleague);

            assertThat(afterOnOwn).as("%s on their own, for %s", key, oldRoles).isEqualTo(beforeOnOwn);
            assertThat(afterOnColleague).as("%s on a colleague, for %s", key, oldRoles)
                    .isEqualTo(beforeOnColleague);
        }
    }

    private boolean beyondTheGuard(Shape shape, AccessPermission permission, Employee concerned) {
        return switch (shape) {
            case GUARD -> true;
            case ON_AN_EMPLOYEE -> regularisations.allowsFor(permission.action(), concerned.getId());
            case WHOLE_REGISTER -> {
                try {
                    regularisations.requireAcrossOrganization(permission.action());
                    yield true;
                } catch (AccessDeniedException refused) {
                    yield false;
                }
            }
        };
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}

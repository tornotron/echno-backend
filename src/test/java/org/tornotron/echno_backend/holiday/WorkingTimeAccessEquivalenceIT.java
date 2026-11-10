package org.tornotron.echno_backend.holiday;

import static java.util.Map.entry;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Moving holidays, the working week, attendance settings and shift timings, on the tenant API and its
 * {@code /web} twin, onto the access matrix changes nobody's access (design note 2.5): every member
 * still reads them through the baseline, and a starting role changes them exactly when its old role
 * did: the system admin and the HR admin.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The
 * area is not scope aware, so the check is on the area alone.
 */
class WorkingTimeAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Any member of the organization, whatever role they hold. */
    private static final Set<String> MEMBERS = Set.of("any member");
    private static final Set<String> ADMINS = Set.of("system-admin", "hr-admin");

    /** Each handler's guard before the move, by controller and handler. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("HolidayController#listForYear", MEMBERS),
            entry("HolidayController#getWorkingWeek", MEMBERS),
            entry("HolidayControllerWeb#listForYear", MEMBERS),
            entry("HolidayControllerWeb#get", MEMBERS),
            entry("HolidayControllerWeb#create", ADMINS),
            entry("HolidayControllerWeb#update", ADMINS),
            entry("HolidayControllerWeb#delete", ADMINS),
            entry("HolidayControllerWeb#getWorkingWeek", MEMBERS),
            entry("HolidayControllerWeb#updateWorkingWeek", ADMINS),
            entry("AttendanceSettingsController#create", ADMINS),
            entry("AttendanceSettingsController#getAll", MEMBERS),
            entry("AttendanceSettingsController#getOrgSettings", MEMBERS),
            entry("AttendanceSettingsController#getProjectSettings", MEMBERS),
            entry("AttendanceSettingsController#update", ADMINS),
            entry("AttendanceSettingsController#deactivate", ADMINS),
            entry("AttendanceSettingsControllerWeb#create", ADMINS),
            entry("AttendanceSettingsControllerWeb#getAll", MEMBERS),
            entry("AttendanceSettingsControllerWeb#getOrgSettings", MEMBERS),
            entry("AttendanceSettingsControllerWeb#getProjectSettings", MEMBERS),
            entry("AttendanceSettingsControllerWeb#update", ADMINS),
            entry("AttendanceSettingsControllerWeb#deactivate", ADMINS),
            entry("ShiftTimingController#create", ADMINS),
            entry("ShiftTimingController#getAll", MEMBERS),
            entry("ShiftTimingController#getById", MEMBERS),
            entry("ShiftTimingController#update", ADMINS),
            entry("ShiftTimingController#delete", ADMINS),
            entry("ShiftTimingControllerWeb#create", ADMINS),
            entry("ShiftTimingControllerWeb#getAll", MEMBERS),
            entry("ShiftTimingControllerWeb#getById", MEMBERS),
            entry("ShiftTimingControllerWeb#update", ADMINS),
            entry("ShiftTimingControllerWeb#delete", ADMINS));

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(WorkingTimeGuardsTest.handlers()).extracting(WorkingTimeGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("working-time-eq-" + role.getGroupName());
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
        Employee person = employee("working-time-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("working-time-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : WorkingTimeGuardsTest.handlers()) {
            String key = WorkingTimeGuardsTest.key(handler);
            Set<String> guard = OLD_GUARDS.get(key);
            boolean before = guard == MEMBERS || guard.stream().anyMatch(oldRoles::contains);

            assertThat(access.check(subject, permissionOf(handler)))
                    .as("%s for %s", key, oldRoles).isEqualTo(before);
        }
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}

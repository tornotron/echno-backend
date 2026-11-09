package org.tornotron.echno_backend.wbs;

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
 * Moving the WBS and the schedule, on the tenant API and its {@code /web} twin, onto the access
 * matrix changes nobody's access (design note 2.5): every member still reads them, through the
 * baseline's wbs:read, and a starting role changes them exactly when its old role did: the system
 * admin and the project manager.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 * The area is not scope aware, so the check is on the area alone.
 */
class WbsAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Any member of the organization, whatever role they hold. */
    private static final Set<String> MEMBERS = Set.of("any member");
    private static final Set<String> ADMIN_OR_PM = Set.of("system-admin", "project-manager");

    /** Each handler's guard before the move, by controller and handler. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("WbsElementController#createWbsElement", ADMIN_OR_PM),
            entry("WbsElementController#bulkCreateWbsElements", ADMIN_OR_PM),
            entry("WbsElementController#getWbsTree", MEMBERS),
            entry("WbsElementController#getWbsFlatList", MEMBERS),
            entry("WbsElementController#getWbsElementById", MEMBERS),
            entry("WbsElementController#updateWbsElement", ADMIN_OR_PM),
            entry("WbsElementController#deleteWbsElement", ADMIN_OR_PM),
            entry("WbsElementController#moveWbsElement", ADMIN_OR_PM),
            entry("WbsElementController#getLeafElements", MEMBERS),
            entry("WbsElementController#recalculateElement", ADMIN_OR_PM),
            entry("WbsElementController#getSchedule", MEMBERS),
            entry("WbsElementController#addDependency", ADMIN_OR_PM),
            entry("WbsElementController#removeDependency", ADMIN_OR_PM),
            entry("WbsElementControllerWeb#createWbsElement", ADMIN_OR_PM),
            entry("WbsElementControllerWeb#bulkCreateWbsElements", ADMIN_OR_PM),
            entry("WbsElementControllerWeb#getWbsTree", MEMBERS),
            entry("WbsElementControllerWeb#getWbsFlatList", MEMBERS),
            entry("WbsElementControllerWeb#getWbsElementById", MEMBERS),
            entry("WbsElementControllerWeb#updateWbsElement", ADMIN_OR_PM),
            entry("WbsElementControllerWeb#deleteWbsElement", ADMIN_OR_PM),
            entry("WbsElementControllerWeb#moveWbsElement", ADMIN_OR_PM),
            entry("WbsElementControllerWeb#getLeafElements", MEMBERS),
            entry("WbsElementControllerWeb#recalculateElement", ADMIN_OR_PM),
            entry("WbsElementControllerWeb#getSchedule", MEMBERS),
            entry("WbsElementControllerWeb#addDependency", ADMIN_OR_PM),
            entry("WbsElementControllerWeb#removeDependency", ADMIN_OR_PM));

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(WbsGuardsTest.handlers()).extracting(WbsGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("wbs-eq-" + role.getGroupName());
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
        Employee person = employee("wbs-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("wbs-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : WbsGuardsTest.handlers()) {
            String key = WbsGuardsTest.key(handler);
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

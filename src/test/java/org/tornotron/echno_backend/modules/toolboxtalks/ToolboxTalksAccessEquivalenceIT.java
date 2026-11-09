package org.tornotron.echno_backend.modules.toolboxtalks;

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
 * Moving toolbox talks, on the tenant API and its {@code /web} twin, onto the access matrix changes
 * nobody's access (design note 2.5): every member still reads them, through the baseline's
 * toolbox-talks:read, and a starting role runs one exactly when its old role did: the system admin,
 * the project manager and the safety officer.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 * The area is not scope aware, so the check is on the area alone.
 */
class ToolboxTalksAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Any member of the organization, whatever role they hold. */
    private static final Set<String> MEMBERS = Set.of("any member");
    private static final Set<String> SAFETY_LEADS = Set.of("system-admin", "project-manager", "safety-officer");

    /** Each handler's guard before the move, by controller and handler. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("ToolboxTalksController#list", MEMBERS),
            entry("ToolboxTalksController#get", MEMBERS),
            entry("ToolboxTalksController#create", SAFETY_LEADS),
            entry("ToolboxTalksController#update", SAFETY_LEADS),
            entry("ToolboxTalksController#addAttendees", SAFETY_LEADS),
            entry("ToolboxTalksController#removeAttendee", SAFETY_LEADS),
            entry("ToolboxTalksController#record", SAFETY_LEADS),
            entry("ToolboxTalksController#listPhotos", MEMBERS),
            entry("ToolboxTalksController#presignPhotos", SAFETY_LEADS),
            entry("ToolboxTalksController#registerPhotos", SAFETY_LEADS),
            entry("ToolboxTalksController#exportPdf", MEMBERS),
            entry("ToolboxTalksControllerWeb#list", MEMBERS),
            entry("ToolboxTalksControllerWeb#get", MEMBERS),
            entry("ToolboxTalksControllerWeb#create", SAFETY_LEADS),
            entry("ToolboxTalksControllerWeb#update", SAFETY_LEADS),
            entry("ToolboxTalksControllerWeb#addAttendees", SAFETY_LEADS),
            entry("ToolboxTalksControllerWeb#removeAttendee", SAFETY_LEADS),
            entry("ToolboxTalksControllerWeb#record", SAFETY_LEADS),
            entry("ToolboxTalksControllerWeb#listPhotos", MEMBERS),
            entry("ToolboxTalksControllerWeb#presignPhotos", SAFETY_LEADS),
            entry("ToolboxTalksControllerWeb#registerPhotos", SAFETY_LEADS),
            entry("ToolboxTalksControllerWeb#exportPdf", MEMBERS));

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(ToolboxTalksGuardsTest.handlers()).extracting(ToolboxTalksGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("toolboxtalks-eq-" + role.getGroupName());
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
        Employee person = employee("toolboxtalks-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("toolboxtalks-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : ToolboxTalksGuardsTest.handlers()) {
            String key = ToolboxTalksGuardsTest.key(handler);
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

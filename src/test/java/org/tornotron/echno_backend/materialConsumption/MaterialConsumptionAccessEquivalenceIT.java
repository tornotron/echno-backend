package org.tornotron.echno_backend.materialConsumption;

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
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;
import org.tornotron.echno_backend.project.Project;

/**
 * Moving material consumption, on the tenant API and its {@code /web} twin, onto the access matrix
 * changes nobody's access (design note 2.5): for every handler, a starting role reaches it exactly
 * when its old role did, and a role with no old counterpart, like the baseline alone, reaches no
 * handler, as before. The store keeper still records and reads consumption, and the project manager
 * still only reads it.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The old
 * answer is worked out from that row, and the new one from the engine on the seeded starting roles, both
 * for the area and for a consumption on a project.
 */
class MaterialConsumptionAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> READERS = Set.of("system-admin", "store-keeper", "project-manager");
    private static final Set<String> WRITERS = Set.of("system-admin", "store-keeper");

    /** Each handler's guard before the move, by controller and handler: one of these old roles. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("MaterialConsumptionController#createMaterialConsumption", WRITERS),
            entry("MaterialConsumptionController#getMaterialConsumptionById", READERS),
            entry("MaterialConsumptionController#getAllMaterialConsumptions", READERS),
            entry("MaterialConsumptionController#getAllMaterialConsumptionsPaginated", READERS),
            entry("MaterialConsumptionController#getConsumptionsByMaterial", READERS),
            entry("MaterialConsumptionController#getConsumptionsByType", READERS),
            entry("MaterialConsumptionController#getConsumptionsByDateRange", READERS),
            entry("MaterialConsumptionController#getConsumptionsByTask", READERS),
            entry("MaterialConsumptionControllerWeb#createMaterialConsumption", WRITERS),
            entry("MaterialConsumptionControllerWeb#getMaterialConsumptionById", READERS),
            entry("MaterialConsumptionControllerWeb#getAllMaterialConsumptions", READERS),
            entry("MaterialConsumptionControllerWeb#getAllMaterialConsumptionsPaginated", READERS),
            entry("MaterialConsumptionControllerWeb#getConsumptionsByMaterial", READERS),
            entry("MaterialConsumptionControllerWeb#getConsumptionsByType", READERS),
            entry("MaterialConsumptionControllerWeb#getConsumptionsByDateRange", READERS),
            entry("MaterialConsumptionControllerWeb#getConsumptionsByTask", READERS));

    @Autowired
    private AccessService access;

    private Project project;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        project = fixtures.project(organization, "Equivalence " + System.nanoTime());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(MaterialConsumptionGuardsTest.handlers()).extracting(MaterialConsumptionGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("mc-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardsGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesNoHandler(String slug) {
        Employee person = employee("mc-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesNoHandler() {
        Employee person = employee("mc-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        // A consumption on a project, linked to nobody: recorded by someone else.
        RecordFacts onAProject = RecordFacts.of(organization.getId(), project.getId());
        for (Method handler : MaterialConsumptionGuardsTest.handlers()) {
            String key = MaterialConsumptionGuardsTest.key(handler);
            boolean before = OLD_GUARDS.get(key).stream().anyMatch(oldRoles::contains);
            AccessPermission permission = permissionOf(handler);

            assertThat(access.check(subject, permission))
                    .as("%s for %s", key, oldRoles).isEqualTo(before);
            assertThat(access.check(subject, permission, onAProject))
                    .as("%s on a consumption for %s", key, oldRoles).isEqualTo(before);
        }
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}

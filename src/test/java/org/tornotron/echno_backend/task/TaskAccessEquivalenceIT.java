package org.tornotron.echno_backend.task;

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
 * Moving tasks, on the tenant API and its {@code /web} twin, onto the access matrix changes nobody's
 * access (design note 2.5): for every handler, a starting role reaches it exactly when its old role
 * did. The reads were open to every member and still are, through the employee baseline, so a role
 * with no old counterpart, like the baseline alone, reads tasks and changes none, as before. Only the
 * system admin and project manager create, change and delete them.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The old
 * answer is worked out from that row, and the new one from the engine on the seeded starting roles, both
 * for the area and for a task on a project.
 */
class TaskAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Stands for the old membership guard, which every member of the organization passed. */
    private static final Set<String> MEMBERS = Set.of("any member");
    private static final Set<String> MANAGERS = Set.of("system-admin", "project-manager");

    /** Each handler's guard before the move, by controller and handler: membership, or one of these old roles. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("TaskController#createTask", MANAGERS),
            entry("TaskController#readAllTasks", MEMBERS),
            entry("TaskController#readATask", MEMBERS),
            entry("TaskController#batchUpdateTasks", MANAGERS),
            entry("TaskController#deleteATask", MANAGERS),
            entry("TaskControllerWeb#createTask", MANAGERS),
            entry("TaskControllerWeb#readAllTasks", MEMBERS),
            entry("TaskControllerWeb#readAllTasksPaginated", MEMBERS),
            entry("TaskControllerWeb#readAllTasksForProject", MEMBERS),
            entry("TaskControllerWeb#readATask", MEMBERS),
            entry("TaskControllerWeb#partialUpdateATask", MANAGERS),
            entry("TaskControllerWeb#batchUpdateTasks", MANAGERS),
            entry("TaskControllerWeb#deleteATask", MANAGERS));

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
        assertThat(TaskGuardsTest.handlers()).extracting(TaskGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("task-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardsGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesOnlyTheReads(String slug) {
        Employee person = employee("task-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesOnlyTheReads() {
        Employee person = employee("task-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        // A task on a project, linked to nobody: created by someone else and assigned to others.
        RecordFacts onAProject = RecordFacts.of(organization.getId(), project.getId());
        for (Method handler : TaskGuardsTest.handlers()) {
            String key = TaskGuardsTest.key(handler);
            Set<String> guard = OLD_GUARDS.get(key);
            // Every person here is an active member of the organization, so the membership guard
            // admitted each of them.
            boolean before = guard == MEMBERS || guard.stream().anyMatch(oldRoles::contains);
            AccessPermission permission = permissionOf(handler);

            assertThat(access.check(subject, permission))
                    .as("%s for %s", key, oldRoles).isEqualTo(before);
            assertThat(access.check(subject, permission, onAProject))
                    .as("%s on a task for %s", key, oldRoles).isEqualTo(before);
        }
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}

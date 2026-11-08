package org.tornotron.echno_backend.project;

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

/**
 * Moving projects, their site structure and the work categories onto the access matrix changes
 * nobody's access (design note 2.5): for every handler of the area, a starting role reaches it exactly
 * when its old role did, and a role with no old counterpart, like the baseline alone, reaches exactly
 * what any member did.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The old
 * answer is worked out from that row, and the new one from the engine on the seeded starting roles,
 * for the area, for a project (and so for its site structure), and for an organization-level record
 * such as a work category.
 */
class ProjectAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> ANY_MEMBER = Set.of();
    private static final Set<String> ADMIN_OR_PM = Set.of("system-admin", "project-manager");

    /**
     * Each handler's guard before the move: any member of the organization, or one of these old roles.
     * Taken from {@code @orgSecurity.isMemberOfCurrentTenant()} and
     * {@code @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')}.
     */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("ProjectControllerWeb#createProject", ADMIN_OR_PM),
            entry("ProjectControllerWeb#readAllProjects", ANY_MEMBER),
            entry("ProjectControllerWeb#readAllProjectsPaginated", ANY_MEMBER),
            entry("ProjectControllerWeb#readAllProjectSummaries", ANY_MEMBER),
            entry("ProjectControllerWeb#readAProject", ANY_MEMBER),
            entry("ProjectControllerWeb#readStatusHistory", ANY_MEMBER),
            entry("ProjectControllerWeb#partialUpdateAProject", ADMIN_OR_PM),
            entry("ProjectControllerWeb#deleteProject", ADMIN_OR_PM),
            entry("ProjectControllerWeb#addEmployeeToProject", ADMIN_OR_PM),
            entry("ProjectControllerWeb#removeEmployeeFromProject", ADMIN_OR_PM),
            entry("ProjectControllerWeb#getEmployeesByProjectId", ANY_MEMBER),
            entry("ProjectControllerWeb#getProjectsByEmployeeId", ANY_MEMBER),
            entry("SpatialNodeController#getTree", ANY_MEMBER),
            entry("SpatialNodeController#getNode", ANY_MEMBER),
            entry("SpatialNodeController#create", ADMIN_OR_PM),
            entry("SpatialNodeController#update", ADMIN_OR_PM),
            entry("SpatialNodeController#move", ADMIN_OR_PM),
            entry("SpatialNodeController#archive", ADMIN_OR_PM),
            entry("SpatialNodeController#restore", ADMIN_OR_PM),
            entry("SpatialNodeController#importRows", ADMIN_OR_PM),
            entry("CategoryControllerWeb#createCategory", ADMIN_OR_PM),
            entry("CategoryControllerWeb#readAllCategories", ANY_MEMBER),
            entry("CategoryControllerWeb#readACategory", ANY_MEMBER),
            entry("CategoryControllerWeb#deleteACategory", ADMIN_OR_PM),
            entry("CategoryControllerWeb#readSubcategories", ANY_MEMBER),
            entry("CategoryController#readSubcategories", ANY_MEMBER),
            // The mobile twins, which development gave the web twins' org-role guards.
            entry("CategoryController#createCategory", ADMIN_OR_PM),
            entry("CategoryController#readAllCategories", ANY_MEMBER),
            entry("CategoryController#readACategory", ANY_MEMBER),
            entry("CategoryController#deleteACategory", ADMIN_OR_PM));

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
        assertThat(ProjectGuardsTest.handlers()).extracting(ProjectGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("project-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardsGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesWhatAnyMemberDid(String slug) {
        Employee person = employee("project-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("project-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        RecordFacts aProject = ProjectAccess.facts(organization.getId(), project.getId(), Set.of(), null);
        RecordFacts aCategory = ProjectAccess.organizationLevel(organization.getId());
        for (Method handler : ProjectGuardsTest.handlers()) {
            String key = ProjectGuardsTest.key(handler);
            Set<String> old = OLD_GUARDS.get(key);
            boolean before = old.isEmpty() || old.stream().anyMatch(oldRoles::contains);
            AccessPermission permission = permissionOf(handler);

            assertThat(access.check(subject, permission))
                    .as("%s for %s", key, oldRoles).isEqualTo(before);
            assertThat(access.check(subject, permission, aProject))
                    .as("%s on a project for %s", key, oldRoles).isEqualTo(before);
            assertThat(access.check(subject, permission, aCategory))
                    .as("%s on an organization-level record for %s", key, oldRoles).isEqualTo(before);
        }
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}

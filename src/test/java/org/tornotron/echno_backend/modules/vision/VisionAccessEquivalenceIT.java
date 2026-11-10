package org.tornotron.echno_backend.modules.vision;

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
 * Moving computer vision onto the access matrix changes nobody's access (design note 2.5): the system
 * admin, project manager, QA engineer and safety officer still read the registry and the settings and
 * ask for runs, and the system admin alone changes the settings. The detection reviewer, which the old
 * guards named but no employee could hold, is a starting role of its own that reaches exactly what
 * the old guards gave that role name.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 * The area is not scope aware, so the check is on the area alone.
 */
class VisionAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> REVIEWERS = Set.of("system-admin", "project-manager", "qa-engineer",
            "safety-officer", "detection-reviewer");
    private static final Set<String> ADMIN = Set.of("system-admin");

    /** Each handler's guard before the move, by controller and handler. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("VisionModelController#list", REVIEWERS),
            entry("VisionModelController#version", REVIEWERS),
            entry("VisionWebController#getSettings", REVIEWERS),
            entry("VisionWebController#updateSettings", ADMIN),
            entry("VisionWebController#queueRun", REVIEWERS));

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(VisionGuardsTest.handlers()).extracting(VisionGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("vision-eq-" + role.getGroupName());
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
        Employee person = employee("vision-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theDetectionReviewerReachesWhatTheOldGuardsGaveThatRoleName() {
        Employee person = employee("vision-eq-detection-reviewer");
        assignments.assignAsSystem(person.getId(), roleId("detection-reviewer"), null);

        assertThat(legacyRowsOf(person)).as("no old role stands behind it").isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of("detection-reviewer"));
    }

    @Test
    void theBaselineAloneReachesNothing() {
        Employee person = employee("vision-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : VisionGuardsTest.handlers()) {
            String key = VisionGuardsTest.key(handler);
            boolean before = OLD_GUARDS.get(key).stream().anyMatch(oldRoles::contains);

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

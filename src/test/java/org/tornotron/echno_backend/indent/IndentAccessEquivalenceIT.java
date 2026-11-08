package org.tornotron.echno_backend.indent;

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
 * Moving the indent handlers onto the access matrix changes nobody's access (design note 2.5): for every
 * converted handler, a starting role reaches it exactly when its old role did, and a role with no old
 * counterpart, like the baseline alone, reaches exactly what any member did, which here is nothing.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The old
 * answer is worked out from that row, and the new one from the engine on the seeded starting roles, both
 * for the area and for an indent on a project raised by someone else.
 */
class IndentAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> STORES_TIER = Set.of("system-admin", "store-keeper", "project-manager");
    private static final Set<String> SYSTEM_ADMIN = Set.of("system-admin");

    /** Each converted handler's guard before the move: one of these old roles. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("IndentControllerWeb.getAllIndents GET /all", STORES_TIER),
            entry("IndentControllerWeb.getAllIndentSummaries GET /summary", STORES_TIER),
            entry("IndentControllerWeb.getAllIndents GET", STORES_TIER),
            entry("IndentControllerWeb.getAnIndent GET /{id}", STORES_TIER),
            entry("IndentControllerWeb.getItems GET /{indentId}/items", STORES_TIER),
            entry("IndentControllerWeb.deleteIndent DELETE /{id}", SYSTEM_ADMIN),
            entry("IndentControllerWeb.deleteItem DELETE /{indentId}/items/{itemId}", SYSTEM_ADMIN),
            entry("IndentItemControllerWeb.deleteIndentItem DELETE /{id}", SYSTEM_ADMIN));

    @Autowired
    private AccessService access;

    private Project project;
    private Employee raiser;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        project = fixtures.project(organization, "Equivalence " + System.nanoTime());
        raiser = employee("indent-eq-raiser");
        flush();
    }

    @Test
    void everyConvertedHandlerHasItsOldGuardRecorded() {
        assertThat(IndentGuardsTest.convertedHandlers().keySet())
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("indent-eq-" + role.getGroupName());
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
        Employee person = employee("indent-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("indent-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        RecordFacts onAProject = IndentAccess.facts(organization.getId(), project.getId(), raiser.getId());
        IndentGuardsTest.convertedHandlers().forEach((signature, handler) -> {
            boolean before = OLD_GUARDS.get(signature).stream().anyMatch(oldRoles::contains);
            AccessPermission permission = permissionOf(handler);

            assertThat(access.check(subject, permission))
                    .as("%s for %s", signature, oldRoles).isEqualTo(before);
            assertThat(access.check(subject, permission, onAProject))
                    .as("%s on an indent for %s", signature, oldRoles).isEqualTo(before);
        });
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}

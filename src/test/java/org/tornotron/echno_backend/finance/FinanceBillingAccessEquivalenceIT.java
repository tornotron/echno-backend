package org.tornotron.echno_backend.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
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
 * Moving customer and construction invoices and payments onto the access matrix changes nobody's
 * access (design note 2.5): every handler of the four controllers was guarded by the system admin or
 * project manager role, and a starting role reaches each one exactly when its old role did, while a
 * role with no old counterpart, like the baseline alone, reaches none of them.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The old
 * answer is worked out from that row, and the new one from the engine on the seeded starting roles, both
 * for the area and for a record on a project.
 */
class FinanceBillingAccessEquivalenceIT extends RoleAssignmentITSupport {

    /**
     * The guard every handler of the four controllers carried before the move:
     * {@code hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')}.
     */
    private static final Set<String> OLD_GUARD = Set.of("system-admin", "project-manager");

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
    void everyHandlerIsCovered() {
        assertThat(FinanceBillingGuardsTest.handlers()).extracting(FinanceBillingGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(FinanceBillingGuardsTest.ACTION_BY_HANDLER.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("billing-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesNoneOfThem(String slug) {
        Employee person = employee("billing-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesNoneOfThem() {
        Employee person = employee("billing-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        // A record on a project, linked to nobody: what a construction invoice or voucher is when it names no one.
        RecordFacts onAProject = RecordFacts.of(organization.getId(), project.getId());
        boolean before = OLD_GUARD.stream().anyMatch(oldRoles::contains);
        for (Method handler : FinanceBillingGuardsTest.handlers()) {
            String key = FinanceBillingGuardsTest.key(handler);
            AccessPermission permission = permissionOf(handler);

            assertThat(access.check(subject, permission))
                    .as("%s for %s", key, oldRoles).isEqualTo(before);
            assertThat(access.check(subject, permission, onAProject))
                    .as("%s on a record for %s", key, oldRoles).isEqualTo(before);
        }
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}

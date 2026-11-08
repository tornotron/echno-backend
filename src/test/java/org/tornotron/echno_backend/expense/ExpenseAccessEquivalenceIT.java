package org.tornotron.echno_backend.expense;

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
 * Moving expenses and receipts onto the access matrix changes nobody's access (design note 2.5): for
 * every handler of either, a starting role reaches it exactly when its old role did, and a role with no
 * old counterpart, like the baseline alone, reaches exactly what any member did.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The old
 * answer is worked out from that row, and the new one from the engine on the seeded starting roles, both
 * for the area and for a record on a project.
 */
class ExpenseAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> ANY_MEMBER = Set.of();
    private static final Set<String> ADMIN_OR_PM = Set.of("system-admin", "project-manager");

    /**
     * Each handler's guard before the move, by controller and handler: any member of the organization
     * ({@code isMemberOfCurrentTenant}), or one of these old roles.
     */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("ExpenseControllerWeb#readAllExpenses", ANY_MEMBER),
            entry("ExpenseControllerWeb#readAllExpensesPaginated", ANY_MEMBER),
            entry("ExpenseControllerWeb#readAnExpense", ANY_MEMBER),
            entry("ExpenseControllerWeb#createExpense", ADMIN_OR_PM),
            entry("ExpenseControllerWeb#updateExpense", ADMIN_OR_PM),
            entry("ExpenseControllerWeb#deleteExpense", ADMIN_OR_PM),
            entry("ReceiptControllerWeb#readAllReceipts", ANY_MEMBER),
            entry("ReceiptControllerWeb#readAllReceiptsPaginated", ANY_MEMBER),
            entry("ReceiptControllerWeb#readAReceipt", ANY_MEMBER),
            entry("ReceiptControllerWeb#createReceipt", ADMIN_OR_PM),
            entry("ReceiptControllerWeb#updateReceipt", ADMIN_OR_PM),
            entry("ReceiptControllerWeb#deleteReceipt", ADMIN_OR_PM));

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
        assertThat(ExpenseGuardsTest.handlers()).extracting(ExpenseGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("expense-eq-" + role.getGroupName());
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
        Employee person = employee("expense-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("expense-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        // A record on a project, linked to nobody: what an expense or a receipt is when it names no one.
        RecordFacts onAProject = RecordFacts.of(organization.getId(), project.getId());
        for (Method handler : ExpenseGuardsTest.handlers()) {
            String key = ExpenseGuardsTest.key(handler);
            Set<String> old = OLD_GUARDS.get(key);
            boolean before = old.isEmpty() || old.stream().anyMatch(oldRoles::contains);
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

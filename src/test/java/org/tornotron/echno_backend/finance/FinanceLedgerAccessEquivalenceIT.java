package org.tornotron.echno_backend.finance;

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
 * Moving the ledger onto the access matrix changes nobody's access (design note 2.5): for every ledger
 * handler, a starting role reaches it exactly when its old role did, and a role with no old counterpart,
 * like the baseline alone, reaches exactly what any member did, which here is none of it.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. The old
 * answer is worked out from that row, and the new one from the engine on the seeded starting roles, both
 * for the area and for a record on a project.
 */
class FinanceLedgerAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> ADMIN = Set.of("system-admin");
    private static final Set<String> ADMIN_OR_PM = Set.of("system-admin", "project-manager");

    /** Each handler's guard before the move, by controller and handler: one of these old roles. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("AccountControllerWeb#list", ADMIN_OR_PM),
            entry("AccountControllerWeb#tree", ADMIN_OR_PM),
            entry("AccountControllerWeb#get", ADMIN_OR_PM),
            entry("AccountControllerWeb#getByCode", ADMIN_OR_PM),
            entry("AccountControllerWeb#export", ADMIN_OR_PM),
            entry("AccountControllerWeb#create", ADMIN_OR_PM),
            entry("AccountControllerWeb#update", ADMIN_OR_PM),
            entry("AccountControllerWeb#deactivate", ADMIN_OR_PM),
            entry("AccountControllerWeb#importCsv", ADMIN),
            entry("AccountControllerWeb#seedDefaults", ADMIN),
            entry("JournalEntryControllerWeb#get", ADMIN_OR_PM),
            entry("JournalEntryControllerWeb#getAll", ADMIN_OR_PM),
            entry("JournalEntryControllerWeb#post", ADMIN_OR_PM),
            entry("JournalEntryControllerWeb#reverse", ADMIN_OR_PM),
            entry("JournalExportControllerWeb#export", ADMIN_OR_PM),
            entry("CustomerControllerWeb#list", ADMIN_OR_PM),
            entry("CustomerControllerWeb#get", ADMIN_OR_PM),
            entry("CustomerControllerWeb#create", ADMIN_OR_PM),
            entry("CustomerControllerWeb#update", ADMIN_OR_PM),
            entry("CustomerControllerWeb#deactivate", ADMIN_OR_PM),
            entry("PostingAccountMappingControllerWeb#list", ADMIN_OR_PM),
            entry("PostingAccountMappingControllerWeb#upsert", ADMIN),
            entry("PostingAccountMappingControllerWeb#delete", ADMIN),
            entry("FinanceSettingsControllerWeb#get", ADMIN_OR_PM),
            entry("FinanceSettingsControllerWeb#update", ADMIN),
            entry("ReportControllerWeb#trialBalance", ADMIN_OR_PM),
            entry("ReportControllerWeb#profitAndLoss", ADMIN_OR_PM),
            entry("ReportControllerWeb#balanceSheet", ADMIN_OR_PM));

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
        assertThat(FinanceLedgerGuardsTest.handlers()).extracting(FinanceLedgerGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("ledger-eq-" + role.getGroupName());
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
        Employee person = employee("ledger-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("ledger-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        RecordFacts onAProject = RecordFacts.of(organization.getId(), project.getId());
        for (Method handler : FinanceLedgerGuardsTest.handlers()) {
            String key = FinanceLedgerGuardsTest.key(handler);
            boolean before = OLD_GUARDS.get(key).stream().anyMatch(oldRoles::contains);
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

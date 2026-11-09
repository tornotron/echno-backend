package org.tornotron.echno_backend.modules.workprogress;

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
 * Moving work progress, the progress inspections and the contractor bills with their payments, onto
 * the access matrix changes nobody's access (design note 2.5): every member still reads them through
 * the baseline's progress:read and progress-bills:read, and still reaches the steps after raising a
 * payment, which the project's named payment authorizer decides. A starting role records progress,
 * prepares and verifies a bill exactly when its old role did (the system admin, the project manager
 * and the site engineer), and sets up a contract's billing, certifies and approves a bill, raises a
 * payment and names a project's authorizer exactly when its old role did (the system admin and the
 * project manager). The finance manager's progress-bills:authorize-payment is asked by no guard, so
 * it reaches what the baseline does.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 * Neither area is scope aware, so the check is on the area alone.
 */
class WorkProgressAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Any member of the organization, whatever role they hold. */
    private static final Set<String> MEMBERS = Set.of("any member");
    private static final Set<String> TEAM = Set.of("system-admin", "project-manager", "site-engineer");
    private static final Set<String> ADMIN_OR_PM = Set.of("system-admin", "project-manager");

    /** Each handler's guard before the move, by controller and handler. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("ProgressInspectionController#list", MEMBERS),
            entry("ProgressInspectionController#get", MEMBERS),
            entry("ProgressInspectionController#record", TEAM),
            entry("ProgressInspectionController#listEvidence", MEMBERS),
            entry("ProgressInspectionController#presignEvidence", TEAM),
            entry("ProgressInspectionController#registerEvidence", TEAM),
            entry("ProgressInspectionControllerWeb#list", MEMBERS),
            entry("ProgressInspectionControllerWeb#get", MEMBERS),
            entry("ProgressInspectionControllerWeb#record", TEAM),
            entry("ProgressInspectionControllerWeb#listEvidence", MEMBERS),
            entry("ProgressInspectionControllerWeb#presignEvidence", TEAM),
            entry("ProgressInspectionControllerWeb#registerEvidence", TEAM),
            entry("ContractBillingController#overview", MEMBERS),
            entry("ContractBillingController#projects", MEMBERS),
            entry("ContractBillingController#project", MEMBERS),
            entry("ContractBillingController#activity", MEMBERS),
            entry("ContractBillingController#contracts", MEMBERS),
            entry("ContractBillingController#contract", MEMBERS),
            entry("ContractBillingController#listBoq", MEMBERS),
            entry("ContractBillingController#addBoqItem", ADMIN_OR_PM),
            entry("ContractBillingController#updateBoqItem", ADMIN_OR_PM),
            entry("ContractBillingController#deleteBoqItem", ADMIN_OR_PM),
            entry("ContractBillingController#listRules", MEMBERS),
            entry("ContractBillingController#addRule", ADMIN_OR_PM),
            entry("ContractBillingController#updateRule", ADMIN_OR_PM),
            entry("ContractBillingController#deleteRule", ADMIN_OR_PM),
            entry("ContractBillingController#listRequirements", MEMBERS),
            entry("ContractBillingController#addRequirement", TEAM),
            entry("ContractBillingController#updateRequirement", TEAM),
            entry("ContractBillingController#deleteRequirement", TEAM),
            entry("ContractBillingController#listBills", MEMBERS),
            entry("ContractBillingController#createBill", TEAM),
            entry("ContractBillingController#getBill", MEMBERS),
            entry("ContractBillingController#updateBill", TEAM),
            entry("ContractBillingController#submitBill", TEAM),
            entry("ContractBillingController#cancelBill", TEAM),
            entry("ContractBillingController#saveMeasurement", TEAM),
            entry("ContractBillingController#verifyBill", TEAM),
            entry("ContractBillingController#returnBill", TEAM),
            entry("ContractBillingController#replaceAdjustments", ADMIN_OR_PM),
            entry("ContractBillingController#certifyBill", ADMIN_OR_PM),
            entry("ContractBillingController#approveBill", ADMIN_OR_PM),
            entry("ContractBillingController#billEvents", MEMBERS),
            entry("ContractBillingController#addNote", TEAM),
            entry("ContractBillingController#billPdf", MEMBERS),
            entry("ContractBillingController#listDocuments", MEMBERS),
            entry("ContractBillingController#presignDocuments", TEAM),
            entry("ContractBillingController#registerDocuments", TEAM),
            entry("ContractBillingController#deleteDocument", TEAM),
            entry("ContractBillingControllerWeb#overview", MEMBERS),
            entry("ContractBillingControllerWeb#projects", MEMBERS),
            entry("ContractBillingControllerWeb#project", MEMBERS),
            entry("ContractBillingControllerWeb#activity", MEMBERS),
            entry("ContractBillingControllerWeb#contracts", MEMBERS),
            entry("ContractBillingControllerWeb#contract", MEMBERS),
            entry("ContractBillingControllerWeb#listBoq", MEMBERS),
            entry("ContractBillingControllerWeb#addBoqItem", ADMIN_OR_PM),
            entry("ContractBillingControllerWeb#updateBoqItem", ADMIN_OR_PM),
            entry("ContractBillingControllerWeb#deleteBoqItem", ADMIN_OR_PM),
            entry("ContractBillingControllerWeb#listRules", MEMBERS),
            entry("ContractBillingControllerWeb#addRule", ADMIN_OR_PM),
            entry("ContractBillingControllerWeb#updateRule", ADMIN_OR_PM),
            entry("ContractBillingControllerWeb#deleteRule", ADMIN_OR_PM),
            entry("ContractBillingControllerWeb#listRequirements", MEMBERS),
            entry("ContractBillingControllerWeb#addRequirement", TEAM),
            entry("ContractBillingControllerWeb#updateRequirement", TEAM),
            entry("ContractBillingControllerWeb#deleteRequirement", TEAM),
            entry("ContractBillingControllerWeb#listBills", MEMBERS),
            entry("ContractBillingControllerWeb#createBill", TEAM),
            entry("ContractBillingControllerWeb#getBill", MEMBERS),
            entry("ContractBillingControllerWeb#updateBill", TEAM),
            entry("ContractBillingControllerWeb#submitBill", TEAM),
            entry("ContractBillingControllerWeb#cancelBill", TEAM),
            entry("ContractBillingControllerWeb#saveMeasurement", TEAM),
            entry("ContractBillingControllerWeb#verifyBill", TEAM),
            entry("ContractBillingControllerWeb#returnBill", TEAM),
            entry("ContractBillingControllerWeb#replaceAdjustments", ADMIN_OR_PM),
            entry("ContractBillingControllerWeb#certifyBill", ADMIN_OR_PM),
            entry("ContractBillingControllerWeb#approveBill", ADMIN_OR_PM),
            entry("ContractBillingControllerWeb#billEvents", MEMBERS),
            entry("ContractBillingControllerWeb#addNote", TEAM),
            entry("ContractBillingControllerWeb#billPdf", MEMBERS),
            entry("ContractBillingControllerWeb#listDocuments", MEMBERS),
            entry("ContractBillingControllerWeb#presignDocuments", TEAM),
            entry("ContractBillingControllerWeb#registerDocuments", TEAM),
            entry("ContractBillingControllerWeb#deleteDocument", TEAM),
            entry("ContractBillPaymentController#list", MEMBERS),
            entry("ContractBillPaymentController#authorizer", MEMBERS),
            entry("ContractBillPaymentController#setAuthorizer", ADMIN_OR_PM),
            entry("ContractBillPaymentController#clearAuthorizer", ADMIN_OR_PM),
            entry("ContractBillPaymentController#raise", ADMIN_OR_PM),
            entry("ContractBillPaymentController#authorize", MEMBERS),
            entry("ContractBillPaymentController#reject", MEMBERS),
            entry("ContractBillPaymentController#record", MEMBERS),
            entry("ContractBillPaymentController#sync", MEMBERS),
            entry("ContractBillPaymentControllerWeb#list", MEMBERS),
            entry("ContractBillPaymentControllerWeb#authorizer", MEMBERS),
            entry("ContractBillPaymentControllerWeb#setAuthorizer", ADMIN_OR_PM),
            entry("ContractBillPaymentControllerWeb#clearAuthorizer", ADMIN_OR_PM),
            entry("ContractBillPaymentControllerWeb#raise", ADMIN_OR_PM),
            entry("ContractBillPaymentControllerWeb#authorize", MEMBERS),
            entry("ContractBillPaymentControllerWeb#reject", MEMBERS),
            entry("ContractBillPaymentControllerWeb#record", MEMBERS),
            entry("ContractBillPaymentControllerWeb#sync", MEMBERS));


    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(WorkProgressGuardsTest.handlers()).extracting(WorkProgressGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("wp-eq-" + role.getGroupName());
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
        Employee person = employee("wp-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("wp-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : WorkProgressGuardsTest.handlers()) {
            String key = WorkProgressGuardsTest.key(handler);
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

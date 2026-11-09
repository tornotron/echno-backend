package org.tornotron.echno_backend.vendor;

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
 * Moving the vendors, on the tenant API and its {@code /web} twin, onto the access matrix changes
 * nobody's access (design note 2.5): for every handler, a starting role reaches it exactly when its old
 * role did, and a role with no old counterpart, like the baseline alone, reaches no handler, as before.
 * In particular the store keeper still reads vendors and their contacts and still does not read a
 * vendor's bank accounts, tax identifiers, payment terms or spend summary.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction. Vendors
 * carry no project, so the area is not scope aware and the check is on the area alone.
 */
class VendorAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> ADMIN = Set.of("system-admin");
    private static final Set<String> ADMIN_OR_KEEPER = Set.of("system-admin", "store-keeper");

    /** Each handler's guard before the move, by controller and handler: one of these old roles. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("VendorController#createVendor", ADMIN),
            entry("VendorController#getVendorById", ADMIN_OR_KEEPER),
            entry("VendorController#getAllVendors", ADMIN_OR_KEEPER),
            entry("VendorController#getAllVendorsPaginated", ADMIN_OR_KEEPER),
            entry("VendorController#searchVendors", ADMIN_OR_KEEPER),
            entry("VendorController#updateVendor", ADMIN),
            entry("VendorController#deleteVendor", ADMIN),
            entry("VendorController#getVendorSummary", ADMIN),
            entry("VendorController#getContacts", ADMIN_OR_KEEPER),
            entry("VendorController#addContact", ADMIN),
            entry("VendorController#updateContact", ADMIN),
            entry("VendorController#deleteContact", ADMIN),
            entry("VendorController#getTaxIdentifiers", ADMIN),
            entry("VendorController#addTaxIdentifier", ADMIN),
            entry("VendorController#updateTaxIdentifier", ADMIN),
            entry("VendorController#deleteTaxIdentifier", ADMIN),
            entry("VendorController#getBankAccounts", ADMIN),
            entry("VendorController#addBankAccount", ADMIN),
            entry("VendorController#updateBankAccount", ADMIN),
            entry("VendorController#deleteBankAccount", ADMIN),
            entry("VendorController#getPaymentTerms", ADMIN),
            entry("VendorController#setPaymentTerms", ADMIN),
            entry("VendorController#deletePaymentTerms", ADMIN),
            entry("VendorControllerWeb#createVendor", ADMIN),
            entry("VendorControllerWeb#getVendorById", ADMIN_OR_KEEPER),
            entry("VendorControllerWeb#getAllVendors", ADMIN_OR_KEEPER),
            entry("VendorControllerWeb#getAllVendorsPaginated", ADMIN_OR_KEEPER),
            entry("VendorControllerWeb#searchVendors", ADMIN_OR_KEEPER),
            entry("VendorControllerWeb#updateVendor", ADMIN),
            entry("VendorControllerWeb#deleteVendor", ADMIN),
            entry("VendorControllerWeb#getVendorSummary", ADMIN),
            entry("VendorControllerWeb#getContacts", ADMIN_OR_KEEPER),
            entry("VendorControllerWeb#addContact", ADMIN),
            entry("VendorControllerWeb#updateContact", ADMIN),
            entry("VendorControllerWeb#deleteContact", ADMIN),
            entry("VendorControllerWeb#getTaxIdentifiers", ADMIN),
            entry("VendorControllerWeb#addTaxIdentifier", ADMIN),
            entry("VendorControllerWeb#updateTaxIdentifier", ADMIN),
            entry("VendorControllerWeb#deleteTaxIdentifier", ADMIN),
            entry("VendorControllerWeb#getBankAccounts", ADMIN),
            entry("VendorControllerWeb#addBankAccount", ADMIN),
            entry("VendorControllerWeb#updateBankAccount", ADMIN),
            entry("VendorControllerWeb#deleteBankAccount", ADMIN),
            entry("VendorControllerWeb#getPaymentTerms", ADMIN),
            entry("VendorControllerWeb#setPaymentTerms", ADMIN),
            entry("VendorControllerWeb#deletePaymentTerms", ADMIN));

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(VendorGuardsTest.handlers()).extracting(VendorGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("vendor-eq-" + role.getGroupName());
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
        Employee person = employee("vendor-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesNoHandler() {
        Employee person = employee("vendor-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : VendorGuardsTest.handlers()) {
            String key = VendorGuardsTest.key(handler);
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

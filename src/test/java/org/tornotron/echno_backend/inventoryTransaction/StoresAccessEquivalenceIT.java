package org.tornotron.echno_backend.inventoryTransaction;

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
 * Moving materials, site transfers, stock adjustments and the stock ledger, on the tenant API and
 * its {@code /web} twin, onto the access matrix changes nobody's access (design note 2.5): a starting
 * role reaches a handler exactly when its old role did, and a member with no stores role reaches
 * none of them.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 * None of the three areas is scope aware, so the check is on the area alone.
 */
class StoresAccessEquivalenceIT extends RoleAssignmentITSupport {

    private static final Set<String> STORES = Set.of("system-admin", "project-manager", "store-keeper");
    private static final Set<String> ADMIN_OR_STORE = Set.of("system-admin", "store-keeper");
    private static final Set<String> ADMIN_OR_PM = Set.of("system-admin", "project-manager");
    private static final Set<String> ADMIN = Set.of("system-admin");

    /** Each handler's guard before the move, by controller and handler. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("InventoryTransactionController#getTransactionById", STORES),
            entry("InventoryTransactionController#getAllTransactions", ADMIN_OR_STORE),
            entry("InventoryTransactionController#getAllTransactionsPaginated", ADMIN_OR_STORE),
            entry("InventoryTransactionController#getTransactionsByMaterial", STORES),
            entry("InventoryTransactionController#getMaterialMovementHistory", STORES),
            entry("InventoryTransactionController#getTransactionsByProject", STORES),
            entry("InventoryTransactionController#getTransactionsByType", ADMIN_OR_STORE),
            entry("InventoryTransactionController#getTransactionsByDateRange", ADMIN_OR_STORE),
            entry("InventoryTransactionController#getTransactionsByStorageLocation", STORES),
            entry("InventoryTransactionController#getStockByStorageLocation", STORES),
            entry("InventoryTransactionController#getStockByMaterial", STORES),
            entry("InventoryTransactionController#getTransactionsByTask", STORES),
            entry("InventoryTransactionController#getTaskMaterialUsageSummary", STORES),
            entry("InventoryTransactionControllerWeb#getTransactionById", STORES),
            entry("InventoryTransactionControllerWeb#getAllTransactions", ADMIN_OR_STORE),
            entry("InventoryTransactionControllerWeb#getAllTransactionsPaginated", ADMIN_OR_STORE),
            entry("InventoryTransactionControllerWeb#getTransactionsByMaterial", STORES),
            entry("InventoryTransactionControllerWeb#getMaterialMovementHistory", STORES),
            entry("InventoryTransactionControllerWeb#getTransactionsByProject", STORES),
            entry("InventoryTransactionControllerWeb#getTransactionsByType", ADMIN_OR_STORE),
            entry("InventoryTransactionControllerWeb#getTransactionsByDateRange", ADMIN_OR_STORE),
            entry("InventoryTransactionControllerWeb#getTransactionsByStorageLocation", STORES),
            entry("InventoryTransactionControllerWeb#getTransactionsByStorageLocationMaterialAndProject", STORES),
            entry("InventoryTransactionControllerWeb#getStockByStorageLocation", STORES),
            entry("InventoryTransactionControllerWeb#getStockByMaterial", STORES),
            entry("InventoryTransactionControllerWeb#getTransactionsByTask", STORES),
            entry("InventoryTransactionControllerWeb#getTaskMaterialUsageSummary", STORES),
            entry("MaterialController#createMaterial", ADMIN_OR_PM),
            entry("MaterialController#getMaterialById", STORES),
            entry("MaterialController#getAllMaterials", STORES),
            entry("MaterialController#getAllMaterialsPaginated", STORES),
            entry("MaterialController#getLowStockMaterials", STORES),
            entry("MaterialController#searchMaterials", STORES),
            entry("MaterialController#getMaterialWithStock", STORES),
            entry("MaterialController#updateMaterial", ADMIN_OR_PM),
            entry("MaterialController#deleteMaterial", ADMIN),
            entry("MaterialController#getLocationThresholds", STORES),
            entry("MaterialController#upsertLocationThreshold", STORES),
            entry("MaterialController#deleteLocationThreshold", ADMIN),
            entry("MaterialControllerWeb#createMaterial", ADMIN_OR_PM),
            entry("MaterialControllerWeb#getMaterialById", STORES),
            entry("MaterialControllerWeb#getAllMaterials", STORES),
            entry("MaterialControllerWeb#getAllMaterialsPaginated", STORES),
            entry("MaterialControllerWeb#getLowStockMaterials", STORES),
            entry("MaterialControllerWeb#getStockSummary", STORES),
            entry("MaterialControllerWeb#searchMaterials", STORES),
            entry("MaterialControllerWeb#getMaterialWithStock", STORES),
            entry("MaterialControllerWeb#updateMaterial", ADMIN_OR_PM),
            entry("MaterialControllerWeb#deleteMaterial", ADMIN),
            entry("MaterialControllerWeb#getLocationThresholds", STORES),
            entry("MaterialControllerWeb#upsertLocationThreshold", STORES),
            entry("MaterialControllerWeb#deleteLocationThreshold", ADMIN),
            entry("SiteTransferController#createSiteTransfer", ADMIN_OR_STORE),
            entry("SiteTransferController#getSiteTransferById", STORES),
            entry("SiteTransferController#getAllSiteTransfers", STORES),
            entry("SiteTransferController#getAllSiteTransfersPaginated", STORES),
            entry("SiteTransferController#getSiteTransfersByStatus", STORES),
            entry("SiteTransferController#getSiteTransfersBySendingProject", STORES),
            entry("SiteTransferController#getSiteTransfersByReceivingProject", STORES),
            entry("SiteTransferController#updateSiteTransferStatus", ADMIN_OR_STORE),
            entry("SiteTransferController#receiveSiteTransfer", ADMIN_OR_STORE),
            entry("SiteTransferController#cancelSiteTransfer", ADMIN_OR_STORE),
            entry("SiteTransferControllerWeb#createSiteTransfer", ADMIN_OR_STORE),
            entry("SiteTransferControllerWeb#getSiteTransferById", STORES),
            entry("SiteTransferControllerWeb#getAllSiteTransfers", STORES),
            entry("SiteTransferControllerWeb#getAllSiteTransfersPaginated", STORES),
            entry("SiteTransferControllerWeb#getSiteTransfersByStatus", STORES),
            entry("SiteTransferControllerWeb#getSiteTransfersBySendingProject", STORES),
            entry("SiteTransferControllerWeb#getSiteTransfersByReceivingProject", STORES),
            entry("SiteTransferControllerWeb#updateSiteTransferStatus", ADMIN_OR_STORE),
            entry("SiteTransferControllerWeb#receiveSiteTransfer", ADMIN_OR_STORE),
            entry("SiteTransferControllerWeb#cancelSiteTransfer", ADMIN_OR_STORE),
            entry("SiteTransferControllerWeb#getSendableAssets", STORES),
            entry("SiteTransferControllerWeb#readStatusHistory", STORES),
            entry("StockAdjustmentControllerWeb#readAllStockAdjustments", STORES),
            entry("StockAdjustmentControllerWeb#readAllStockAdjustmentsPaginated", STORES),
            entry("StockAdjustmentControllerWeb#readAStockAdjustment", STORES),
            entry("StockAdjustmentControllerWeb#readStockAdjustmentsBySourceDocument", STORES),
            entry("StockAdjustmentControllerWeb#createStockAdjustment", STORES),
            entry("StockAdjustmentControllerWeb#updateStockAdjustment", STORES),
            entry("StockAdjustmentControllerWeb#approveStockAdjustment", ADMIN_OR_PM),
            entry("StockAdjustmentControllerWeb#rejectStockAdjustment", ADMIN_OR_PM),
            entry("StockAdjustmentControllerWeb#deleteStockAdjustment", ADMIN_OR_PM));

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(StoresGuardsTest.handlers()).extracting(StoresGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("stores-eq-" + role.getGroupName());
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
        Employee person = employee("stores-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesNoneOfIt() {
        Employee person = employee("stores-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : StoresGuardsTest.handlers()) {
            String key = StoresGuardsTest.key(handler);
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

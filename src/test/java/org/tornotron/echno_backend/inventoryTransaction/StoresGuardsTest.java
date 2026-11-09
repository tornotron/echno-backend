package org.tornotron.echno_backend.inventoryTransaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.material.MaterialController;
import org.tornotron.echno_backend.material.MaterialControllerWeb;
import org.tornotron.echno_backend.siteTransfer.SiteTransferController;
import org.tornotron.echno_backend.siteTransfer.SiteTransferControllerWeb;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentController;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentControllerWeb;

/**
 * Every stores handler, on the tenant API and its {@code /web} twin, asks the access matrix for the
 * one action it performs, and nothing else. Stock adjustments and the ledger are the inventory area,
 * with the organization-wide ledger listings on inventory:read-ledger; the catalogue is the materials
 * area, with a reorder threshold on materials:set-thresholds; site transfers are an area of their
 * own. A new handler fails here until it is given its row.
 */
class StoresGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by area and action. */
    static final Map<String, String> PERMISSION_BY_HANDLER = Map.ofEntries(
Map.entry("InventoryTransactionController#getTransactionById", "inventory:read"),
            Map.entry("InventoryTransactionController#getAllTransactions", "inventory:read-ledger"),
            Map.entry("InventoryTransactionController#getAllTransactionsPaginated", "inventory:read-ledger"),
            Map.entry("InventoryTransactionController#getTransactionsByMaterial", "inventory:read"),
            Map.entry("InventoryTransactionController#getMaterialMovementHistory", "inventory:read"),
            Map.entry("InventoryTransactionController#getTransactionsByProject", "inventory:read"),
            Map.entry("InventoryTransactionController#getTransactionsByType", "inventory:read-ledger"),
            Map.entry("InventoryTransactionController#getTransactionsByDateRange", "inventory:read-ledger"),
            Map.entry("InventoryTransactionController#getTransactionsByStorageLocation", "inventory:read"),
            Map.entry("InventoryTransactionController#getStockByStorageLocation", "inventory:read"),
            Map.entry("InventoryTransactionController#getStockByMaterial", "inventory:read"),
            Map.entry("InventoryTransactionController#getTransactionsByTask", "inventory:read"),
            Map.entry("InventoryTransactionController#getTaskMaterialUsageSummary", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getTransactionById", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getAllTransactions", "inventory:read-ledger"),
            Map.entry("InventoryTransactionControllerWeb#getAllTransactionsPaginated", "inventory:read-ledger"),
            Map.entry("InventoryTransactionControllerWeb#getTransactionsByMaterial", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getMaterialMovementHistory", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getTransactionsByProject", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getTransactionsByType", "inventory:read-ledger"),
            Map.entry("InventoryTransactionControllerWeb#getTransactionsByDateRange", "inventory:read-ledger"),
            Map.entry("InventoryTransactionControllerWeb#getTransactionsByStorageLocation", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getTransactionsByStorageLocationMaterialAndProject", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getStockByStorageLocation", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getStockByMaterial", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getTransactionsByTask", "inventory:read"),
            Map.entry("InventoryTransactionControllerWeb#getTaskMaterialUsageSummary", "inventory:read"),
            Map.entry("MaterialController#createMaterial", "materials:create"),
            Map.entry("MaterialController#getMaterialById", "materials:read"),
            Map.entry("MaterialController#getAllMaterials", "materials:read"),
            Map.entry("MaterialController#getAllMaterialsPaginated", "materials:read"),
            Map.entry("MaterialController#getLowStockMaterials", "materials:read"),
            Map.entry("MaterialController#searchMaterials", "materials:read"),
            Map.entry("MaterialController#getMaterialWithStock", "materials:read"),
            Map.entry("MaterialController#updateMaterial", "materials:edit"),
            Map.entry("MaterialController#deleteMaterial", "materials:delete"),
            Map.entry("MaterialController#getLocationThresholds", "materials:read"),
            Map.entry("MaterialController#upsertLocationThreshold", "materials:set-thresholds"),
            Map.entry("MaterialController#deleteLocationThreshold", "materials:delete"),
            Map.entry("MaterialControllerWeb#createMaterial", "materials:create"),
            Map.entry("MaterialControllerWeb#getMaterialById", "materials:read"),
            Map.entry("MaterialControllerWeb#getAllMaterials", "materials:read"),
            Map.entry("MaterialControllerWeb#getAllMaterialsPaginated", "materials:read"),
            Map.entry("MaterialControllerWeb#getLowStockMaterials", "materials:read"),
            Map.entry("MaterialControllerWeb#getStockSummary", "materials:read"),
            Map.entry("MaterialControllerWeb#searchMaterials", "materials:read"),
            Map.entry("MaterialControllerWeb#getMaterialWithStock", "materials:read"),
            Map.entry("MaterialControllerWeb#updateMaterial", "materials:edit"),
            Map.entry("MaterialControllerWeb#deleteMaterial", "materials:delete"),
            Map.entry("MaterialControllerWeb#getLocationThresholds", "materials:read"),
            Map.entry("MaterialControllerWeb#upsertLocationThreshold", "materials:set-thresholds"),
            Map.entry("MaterialControllerWeb#deleteLocationThreshold", "materials:delete"),
            Map.entry("SiteTransferController#createSiteTransfer", "site-transfers:create"),
            Map.entry("SiteTransferController#getSiteTransferById", "site-transfers:read"),
            Map.entry("SiteTransferController#getAllSiteTransfers", "site-transfers:read"),
            Map.entry("SiteTransferController#getAllSiteTransfersPaginated", "site-transfers:read"),
            Map.entry("SiteTransferController#getSiteTransfersByStatus", "site-transfers:read"),
            Map.entry("SiteTransferController#getSiteTransfersBySendingProject", "site-transfers:read"),
            Map.entry("SiteTransferController#getSiteTransfersByReceivingProject", "site-transfers:read"),
            Map.entry("SiteTransferController#updateSiteTransferStatus", "site-transfers:edit"),
            Map.entry("SiteTransferController#receiveSiteTransfer", "site-transfers:edit"),
            Map.entry("SiteTransferController#cancelSiteTransfer", "site-transfers:edit"),
            Map.entry("SiteTransferControllerWeb#createSiteTransfer", "site-transfers:create"),
            Map.entry("SiteTransferControllerWeb#getSiteTransferById", "site-transfers:read"),
            Map.entry("SiteTransferControllerWeb#getAllSiteTransfers", "site-transfers:read"),
            Map.entry("SiteTransferControllerWeb#getAllSiteTransfersPaginated", "site-transfers:read"),
            Map.entry("SiteTransferControllerWeb#getSiteTransfersByStatus", "site-transfers:read"),
            Map.entry("SiteTransferControllerWeb#getSiteTransfersBySendingProject", "site-transfers:read"),
            Map.entry("SiteTransferControllerWeb#getSiteTransfersByReceivingProject", "site-transfers:read"),
            Map.entry("SiteTransferControllerWeb#updateSiteTransferStatus", "site-transfers:edit"),
            Map.entry("SiteTransferControllerWeb#receiveSiteTransfer", "site-transfers:edit"),
            Map.entry("SiteTransferControllerWeb#cancelSiteTransfer", "site-transfers:edit"),
            Map.entry("SiteTransferControllerWeb#getSendableAssets", "site-transfers:read"),
            Map.entry("SiteTransferControllerWeb#readStatusHistory", "site-transfers:read"),
            Map.entry("StockAdjustmentControllerWeb#readAllStockAdjustments", "inventory:read"),
            Map.entry("StockAdjustmentControllerWeb#readAllStockAdjustmentsPaginated", "inventory:read"),
            Map.entry("StockAdjustmentControllerWeb#readAStockAdjustment", "inventory:read"),
            Map.entry("StockAdjustmentControllerWeb#readStockAdjustmentsBySourceDocument", "inventory:read"),
            Map.entry("StockAdjustmentControllerWeb#createStockAdjustment", "inventory:create"),
            Map.entry("StockAdjustmentControllerWeb#updateStockAdjustment", "inventory:edit"),
            Map.entry("StockAdjustmentControllerWeb#approveStockAdjustment", "inventory:approve"),
            Map.entry("StockAdjustmentControllerWeb#rejectStockAdjustment", "inventory:approve"),
            Map.entry("StockAdjustmentControllerWeb#deleteStockAdjustment", "inventory:delete"));

    @Test
    void everyHandlerNeedsTheStoresActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(StoresGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(PERMISSION_BY_HANDLER.keySet());
        PERMISSION_BY_HANDLER.forEach((handler, permission) -> {
            String[] parts = permission.split(":");
            assertThat(guards.get(handler)).as(handler)
                    .isEqualTo("@access.can('" + parts[0] + "', '" + parts[1] + "')");
        });
    }

    @Test
    void theMobileStockAdjustmentTwinDeclaresNoHandlerOfItsOwn() {
        // It inherits the /web twin's handlers, so the guards above are the ones it serves.
        assertThat(StockAdjustmentControllerWeb.class).isAssignableFrom(StockAdjustmentController.class);
        assertThat(Arrays.stream(StockAdjustmentController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))).isEmpty();
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(InventoryTransactionController.class, InventoryTransactionControllerWeb.class,
                        MaterialController.class, MaterialControllerWeb.class,
                        SiteTransferController.class, SiteTransferControllerWeb.class,
                        StockAdjustmentControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}

package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the whole of what the {@code store-keeper} role can reach, in one list.
 *
 * <p>The role exists because the Resources domain had nothing between plain organization membership
 * and {@code system-admin}, so the only way to let somebody book a delivery was to make them an
 * administrator of the organization. A role that narrow is only worth having while it stays narrow,
 * and the way it stops being narrow is one guard at a time, each addition reasonable on its own.
 *
 * <p>So this test compares the two directions at once. The expected table below is the whole grant,
 * and it is asserted to equal what the source actually carries, which means an endpoint added to the
 * role and an endpoint removed from it both fail here. A widening is then a deliberate edit to this
 * table with a reason attached, rather than a line in a diff nobody counts.
 *
 * <p>It reads the source rather than the loaded classes on purpose. Every entry is one
 * {@code @PreAuthorize} string sitting above one mapping, which is exactly what is being pinned, and
 * a text scan covers controllers this test does not have to know the names of: a guard added
 * anywhere under {@code src/main} shows up whether or not somebody remembered this file.
 */
class StoreKeeperRoleScopeTest {

    private static final Path SOURCE_ROOT = Path.of(
            System.getProperty("main.source.root", "src/main/java"));

    private static final String ROLE = "store-keeper";

    /**
     * Every endpoint the role may reach, as {@code CONTROLLER.method}.
     *
     * <p>The reads are here because a form that cannot be filled in is the same refusal one screen
     * earlier: a receipt names a purchase order, a vendor, a project, a storage location and a
     * material, and every one of those is a lookup the browser makes before the storekeeper can type
     * anything. Opening the write and leaving the lookups shut moves the 403 rather than removing
     * it, which is the failure this codebase has already made once.
     */
    private static final Set<String> EXPECTED = new TreeSet<>(Set.of(
            // Recording a delivery, which is the case the role was introduced for.
            "GoodsReceivedNoteControllerWeb.createGrn",
            "GoodsReceivedNoteControllerWeb.updateGrn",
            "GoodsReceivedNoteControllerWeb.getGrnById",
            "GoodsReceivedNoteControllerWeb.getAllGrns",
            "GoodsReceivedNoteControllerWeb.getAllGrnsPaginated",
            "GoodsReceivedNoteControllerWeb.getGrnsByVendor",
            "GoodsReceivedNoteControllerWeb.getGrnsByDateRange",

            // Handing material out over the counter.
            "MaterialConsumptionControllerWeb.createMaterialConsumption",
            "MaterialConsumptionControllerWeb.getMaterialConsumptionById",
            "MaterialConsumptionControllerWeb.getAllMaterialConsumptions",
            "MaterialConsumptionControllerWeb.getAllMaterialConsumptionsPaginated",
            "MaterialConsumptionControllerWeb.getConsumptionsByMaterial",
            "MaterialConsumptionControllerWeb.getConsumptionsByType",
            "MaterialConsumptionControllerWeb.getConsumptionsByDateRange",
            "MaterialConsumptionControllerWeb.getConsumptionsByTask",

            // Both ends of a site transfer are worked by a store: one despatches, one confirms.
            // The status route is a signpost rather than an authority. It refuses every payload and
            // answers by naming the receive and cancel routes, which is a better reply than a 403
            // to a caller who holds both of those.
            "SiteTransferControllerWeb.createSiteTransfer",
            "SiteTransferControllerWeb.receiveSiteTransfer",
            "SiteTransferControllerWeb.cancelSiteTransfer",
            "SiteTransferControllerWeb.updateSiteTransferStatus",
            "SiteTransferControllerWeb.getSiteTransferById",
            "SiteTransferControllerWeb.getAllSiteTransfers",
            "SiteTransferControllerWeb.getAllSiteTransfersPaginated",
            "SiteTransferControllerWeb.getSiteTransfersByStatus",
            "SiteTransferControllerWeb.getSiteTransfersBySendingProject",
            "SiteTransferControllerWeb.getSiteTransfersByReceivingProject",
            "SiteTransferControllerWeb.readStatusHistory",

            // Taking the count and writing down what it found. Approving it is somebody else's.
            "StockAdjustmentControllerWeb.createStockAdjustment",
            "StockAdjustmentControllerWeb.updateStockAdjustment",

            // The catalogue and the balances held against it, which every stores form names.
            "MaterialControllerWeb.getMaterialById",
            "MaterialControllerWeb.getAllMaterials",
            "MaterialControllerWeb.getAllMaterialsPaginated",
            "MaterialControllerWeb.getLowStockMaterials",
            "MaterialControllerWeb.getStockSummary",
            "MaterialControllerWeb.searchMaterials",
            "MaterialControllerWeb.getMaterialWithStock",
            "MaterialControllerWeb.getLocationThresholds",

            // Where the material came from and where it is going.
            "StorageLocationControllerWeb.getStorageLocationById",
            "StorageLocationControllerWeb.getAllStorageLocations",
            "StorageLocationControllerWeb.getAllStorageLocationsPaginated",
            "StorageLocationControllerWeb.getStorageLocationsByProject",
            "StorageLocationControllerWeb.getStorageLocationsByType",

            // The ledger the storekeeper's own documents post into.
            "InventoryTransactionControllerWeb.getTransactionById",
            "InventoryTransactionControllerWeb.getAllTransactions",
            "InventoryTransactionControllerWeb.getAllTransactionsPaginated",
            "InventoryTransactionControllerWeb.getTransactionsByMaterial",
            "InventoryTransactionControllerWeb.getMaterialMovementHistory",
            "InventoryTransactionControllerWeb.getTransactionsByProject",
            "InventoryTransactionControllerWeb.getTransactionsByType",
            "InventoryTransactionControllerWeb.getTransactionsByDateRange",
            "InventoryTransactionControllerWeb.getTransactionsByStorageLocation",
            "InventoryTransactionControllerWeb.getTransactionsByStorageLocationMaterialAndProject",
            "InventoryTransactionControllerWeb.getStockByStorageLocation",
            "InventoryTransactionControllerWeb.getStockByMaterial",
            "InventoryTransactionControllerWeb.getTransactionsByTask",
            "InventoryTransactionControllerWeb.getTaskMaterialUsageSummary",

            // What was ordered, so a delivery can be checked against it. Read only: placing and
            // changing the order is procurement's, and the receiving end must not be able to edit
            // the figures it is being measured against. Both twins carry the org-role guard, so
            // both get the read.
            "PurchaseOrderControllerWeb.getPurchaseOrderById",
            "PurchaseOrderControllerWeb.getAllPurchaseOrders",
            "PurchaseOrderControllerWeb.getAllPurchaseOrdersPaginated",
            "PurchaseOrderControllerWeb.getPurchaseOrdersByVendor",
            "PurchaseOrderControllerWeb.getPurchaseOrdersByIndent",
            "PurchaseOrderControllerWeb.getPurchaseOrdersByStatus",
            "PurchaseOrderControllerWeb.readStatusHistory",
            "PurchaseOrderController.getPurchaseOrderById",
            "PurchaseOrderController.getAllPurchaseOrders",
            "PurchaseOrderController.getAllPurchaseOrdersPaginated",
            "PurchaseOrderController.getPurchaseOrdersByVendor",
            "PurchaseOrderController.getPurchaseOrdersByIndent",
            "PurchaseOrderController.getPurchaseOrdersByStatus",
            "PurchaseOrderItemControllerWeb.getPurchaseOrderItemById",
            "PurchaseOrderItemControllerWeb.getAllPurchaseOrderItems",
            "PurchaseOrderItemControllerWeb.getPurchaseOrderItemsPaginated",
            "PurchaseOrderItemControllerWeb.getItemsByPurchaseOrderId",
            "PurchaseOrderItemControllerWeb.getItemsByMaterialId",
            "PurchaseOrderItemController.getPurchaseOrderItemById",
            "PurchaseOrderItemController.getAllPurchaseOrderItems",
            "PurchaseOrderItemController.getPurchaseOrderItemsPaginated",
            "PurchaseOrderItemController.getItemsByPurchaseOrderId",
            "PurchaseOrderItemController.getItemsByMaterialId",

            // What has been requisitioned, so the store knows what a delivery answers and what is
            // still outstanding. Raising a requisition is not on this list.
            "IndentControllerWeb.getAllIndents",
            "IndentControllerWeb.getAllIndentSummaries",
            "IndentControllerWeb.getAnIndent",
            "IndentControllerWeb.getItems",
            "IndentItemControllerWeb.getIndentItemById",
            "IndentItemControllerWeb.getAllIndentItems",
            "IndentItemControllerWeb.getIndentItemsPaginated",
            "IndentItemControllerWeb.getIndentItemsByIndentId",
            "IndentItemControllerWeb.getIndentItemsByMaterialId",
            "IndentItemControllerWeb.getIndentItemsByConversionStatus",

            // Who brought the delivery, and how to reach them about it. The vendor summary, bank
            // accounts, tax identifiers and payment terms are commercial rather than stores
            // information and are deliberately absent.
            "VendorControllerWeb.getVendorById",
            "VendorControllerWeb.getAllVendors",
            "VendorControllerWeb.getAllVendorsPaginated",
            "VendorControllerWeb.searchVendors",
            "VendorControllerWeb.getContacts"
    ));

    /** One granted endpoint: the controller it sits on, the method, and the mapping above it. */
    private record Granted(String controller, String method, String mapping) {
        String signature() {
            return controller + "." + method;
        }
    }

    private static final Pattern PRE_AUTHORIZE = Pattern.compile("^\\s*@PreAuthorize\\((.*)\\)\\s*$");
    private static final Pattern MAPPING = Pattern.compile("^\\s*@(Get|Post|Put|Patch|Delete)Mapping\\b.*");
    private static final Pattern METHOD = Pattern.compile("^\\s*(?:public|protected)\\s+.*?\\b(\\w+)\\s*\\(.*");

    private List<Granted> grantedEndpoints() throws IOException {
        List<Granted> granted = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                granted.addAll(grantedIn(file));
            }
        }
        return granted;
    }

    private List<Granted> grantedIn(Path file) throws IOException {
        String controller = file.getFileName().toString().replace(".java", "");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Granted> granted = new ArrayList<>();

        boolean carriesRole = false;
        String mapping = null;
        for (String line : lines) {
            Matcher pre = PRE_AUTHORIZE.matcher(line);
            if (pre.matches()) {
                carriesRole = pre.group(1).contains("'" + ROLE + "'");
                continue;
            }
            Matcher map = MAPPING.matcher(line);
            if (map.matches()) {
                mapping = map.group(1).toUpperCase();
                continue;
            }
            Matcher method = METHOD.matcher(line);
            if (method.matches()) {
                if (carriesRole) {
                    granted.add(new Granted(controller, method.group(1), mapping == null ? "?" : mapping));
                }
                carriesRole = false;
                mapping = null;
            }
        }
        return granted;
    }

    @Test
    void theRoleReachesExactlyTheEndpointsTheStoresWorkflowNeeds() throws IOException {
        Set<String> actual = new TreeSet<>();
        for (Granted g : grantedEndpoints()) {
            actual.add(g.signature());
        }

        assertThat(actual)
                .as("endpoints guarded on the '%s' role. Adding one is a widening of the role and "
                        + "belongs in the table in this test with a reason; removing one is a "
                        + "capability the storekeeper workflow silently lost", ROLE)
                .isEqualTo(EXPECTED);
    }

    @Test
    void theRoleNeverApprovesRejectsOrDeletesAnything() throws IOException {
        // The storekeeper counts the shelf and writes down what they found. Approving the count is
        // what posts the balance, and it is meant to be somebody else reading the document. The
        // SelfApprovalPolicy already refuses a caller approving their own, but it is consulted one
        // request too late to help if the role holds both halves: two storekeepers would simply
        // approve each other's, and the control would be gone with the policy still passing.
        List<String> forbidden = new ArrayList<>();
        for (Granted g : grantedEndpoints()) {
            String method = g.method().toLowerCase();
            if (method.startsWith("approve") || method.startsWith("reject")
                    || method.startsWith("delete") || "DELETE".equals(g.mapping())) {
                forbidden.add(g.signature() + " (" + g.mapping() + ")");
            }
        }

        assertThat(forbidden)
                .as("endpoints on the '%s' role that approve, reject or delete", ROLE)
                .isEmpty();
    }
}

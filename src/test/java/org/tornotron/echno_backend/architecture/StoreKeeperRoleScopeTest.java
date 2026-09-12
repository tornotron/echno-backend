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
     * Every endpoint the role may reach, as {@code CONTROLLER.method VERB path}.
     *
     * <p>The reads are here because a form that cannot be filled in is the same refusal one screen
     * earlier: a receipt names a purchase order, a vendor, a project, a storage location and a
     * material, and every one of those is a lookup the browser makes before the storekeeper can type
     * anything. Opening the write and leaving the lookups shut moves the 403 rather than removing
     * it, which is the failure this codebase has already made once.
     */
    private static final Set<String> EXPECTED = new TreeSet<>(Set.of(
            // Recording a delivery, which is the case the role was introduced for.
            "GoodsReceivedNoteControllerWeb.createGrn POST",
            "GoodsReceivedNoteControllerWeb.getGrnById GET /{id}",
            "GoodsReceivedNoteControllerWeb.getAllGrns GET",
            "GoodsReceivedNoteControllerWeb.getAllGrnsPaginated GET /all",
            "GoodsReceivedNoteControllerWeb.getGrnsByVendor GET /vendor/{vendorId}",
            "GoodsReceivedNoteControllerWeb.updateGrn PATCH",
            "GoodsReceivedNoteControllerWeb.getGrnsByDateRange GET /date-range",

            // Handing material out over the counter.
            "MaterialConsumptionControllerWeb.createMaterialConsumption POST",
            "MaterialConsumptionControllerWeb.getMaterialConsumptionById GET /{id}",
            "MaterialConsumptionControllerWeb.getAllMaterialConsumptions GET",
            "MaterialConsumptionControllerWeb.getAllMaterialConsumptionsPaginated GET /all",
            "MaterialConsumptionControllerWeb.getConsumptionsByMaterial GET /material/{materialId}",
            "MaterialConsumptionControllerWeb.getConsumptionsByType GET /type/{consumptionType}",
            "MaterialConsumptionControllerWeb.getConsumptionsByDateRange GET /date-range",
            "MaterialConsumptionControllerWeb.getConsumptionsByTask GET /task/{taskId}",

            // Both ends of a site transfer are worked by a store: one despatches, one confirms.
            // The status route is a signpost rather than an authority. It refuses every payload and
            // answers by naming the receive and cancel routes, which is a better reply than a 403
            // to a caller who holds both of those.
            "SiteTransferControllerWeb.createSiteTransfer POST",
            "SiteTransferControllerWeb.getSiteTransferById GET /{id}",
            "SiteTransferControllerWeb.getAllSiteTransfers GET",
            "SiteTransferControllerWeb.getAllSiteTransfersPaginated GET /all",
            "SiteTransferControllerWeb.getSiteTransfersByStatus GET /status/{status}",
            "SiteTransferControllerWeb.getSiteTransfersBySendingProject GET /sending-project/{projectId}",
            "SiteTransferControllerWeb.getSiteTransfersByReceivingProject GET /receiving-project/{projectId}",
            "SiteTransferControllerWeb.updateSiteTransferStatus PATCH /{id}/status",
            "SiteTransferControllerWeb.receiveSiteTransfer POST /{id}/receive",
            "SiteTransferControllerWeb.cancelSiteTransfer POST /{id}/cancel",
            "SiteTransferControllerWeb.readStatusHistory GET /{id}/status-history",

            // Taking the count and writing down what it found. Approving it is somebody else's.
            "StockAdjustmentControllerWeb.createStockAdjustment POST",
            "StockAdjustmentControllerWeb.updateStockAdjustment PUT /{id}",

            // The catalogue and the balances held against it, which every stores form names.
            "MaterialControllerWeb.getMaterialById GET /{id}",
            "MaterialControllerWeb.getAllMaterials GET",
            "MaterialControllerWeb.getAllMaterialsPaginated GET /all",
            "MaterialControllerWeb.getLowStockMaterials GET /low-stock",
            "MaterialControllerWeb.getStockSummary GET /summary",
            "MaterialControllerWeb.searchMaterials GET /search",
            "MaterialControllerWeb.getMaterialWithStock GET /{id}/stock",
            "MaterialControllerWeb.getLocationThresholds GET /{materialId}/location-thresholds",
            // Reorder thresholds at a storage location are the one catalogue write on the role,
            // decided on #650: tuning them per project is a stores decision, and the low-stock
            // sweep that reads them notifies the same people. Removing an override is not here.
            "MaterialControllerWeb.upsertLocationThreshold PUT /{materialId}/location-thresholds/{storageLocationId}",

            // Where the material came from and where it is going.
            "StorageLocationControllerWeb.getStorageLocationById GET /{id}",
            "StorageLocationControllerWeb.getAllStorageLocations GET",
            "StorageLocationControllerWeb.getAllStorageLocationsPaginated GET /all",
            "StorageLocationControllerWeb.getStorageLocationsByProject GET /project/{projectId}",
            "StorageLocationControllerWeb.getStorageLocationsByType GET /type/{locationType}",

            // The ledger the storekeeper's own documents post into.
            "InventoryTransactionControllerWeb.getTransactionById GET /{id}",
            "InventoryTransactionControllerWeb.getAllTransactions GET",
            "InventoryTransactionControllerWeb.getAllTransactionsPaginated GET /all",
            "InventoryTransactionControllerWeb.getTransactionsByMaterial GET /material/{materialId}",
            "InventoryTransactionControllerWeb.getMaterialMovementHistory GET /material/{materialId}/history",
            "InventoryTransactionControllerWeb.getTransactionsByProject GET /project/{projectId}",
            "InventoryTransactionControllerWeb.getTransactionsByType GET /type/{transactionType}",
            "InventoryTransactionControllerWeb.getTransactionsByDateRange GET /date-range",
            "InventoryTransactionControllerWeb.getTransactionsByStorageLocation GET /storage-location/{storageLocationId}",
            "InventoryTransactionControllerWeb.getTransactionsByStorageLocationMaterialAndProject GET /storage-location/{storageLocationId}/material/{materialId}/project/{projectId}",
            "InventoryTransactionControllerWeb.getStockByStorageLocation GET /storage-location/{storageLocationId}/stock",
            "InventoryTransactionControllerWeb.getStockByMaterial GET /material/{materialId}/stock",
            "InventoryTransactionControllerWeb.getTransactionsByTask GET /task/{taskId}",
            "InventoryTransactionControllerWeb.getTaskMaterialUsageSummary GET /project/{projectId}/task-summary",

            // What was ordered, so a delivery can be checked against it. Read only: placing and
            // changing the order is procurement's, and the receiving end must not be able to alter
            // the figures it is being measured against. Both twins carry the org-role guard, so
            // both get the read.
            "PurchaseOrderControllerWeb.getPurchaseOrderById GET /{id}",
            "PurchaseOrderControllerWeb.readStatusHistory GET /{id}/status-history",
            "PurchaseOrderControllerWeb.getAllPurchaseOrders GET",
            "PurchaseOrderControllerWeb.getAllPurchaseOrdersPaginated GET /all",
            "PurchaseOrderControllerWeb.getPurchaseOrdersByVendor GET /vendor/{vendorId}",
            "PurchaseOrderControllerWeb.getPurchaseOrdersByIndent GET /indent/{indentId}",
            "PurchaseOrderControllerWeb.getPurchaseOrdersByStatus GET /status/{status}",
            "PurchaseOrderController.getPurchaseOrderById GET /{id}",
            "PurchaseOrderController.getAllPurchaseOrders GET",
            "PurchaseOrderController.getAllPurchaseOrdersPaginated GET /all",
            "PurchaseOrderController.getPurchaseOrdersByVendor GET /vendor/{vendorId}",
            "PurchaseOrderController.getPurchaseOrdersByIndent GET /indent/{indentId}",
            "PurchaseOrderController.getPurchaseOrdersByStatus GET /status/{status}",
            "PurchaseOrderItemControllerWeb.getPurchaseOrderItemById GET /{id}",
            "PurchaseOrderItemControllerWeb.getAllPurchaseOrderItems GET",
            "PurchaseOrderItemControllerWeb.getPurchaseOrderItemsPaginated GET /paginated",
            "PurchaseOrderItemControllerWeb.getItemsByPurchaseOrderId GET /purchase-order/{purchaseOrderId}",
            "PurchaseOrderItemControllerWeb.getItemsByMaterialId GET /material/{materialId}",
            "PurchaseOrderItemController.getPurchaseOrderItemById GET /{id}",
            "PurchaseOrderItemController.getAllPurchaseOrderItems GET",
            "PurchaseOrderItemController.getPurchaseOrderItemsPaginated GET /paginated",
            "PurchaseOrderItemController.getItemsByPurchaseOrderId GET /purchase-order/{purchaseOrderId}",
            "PurchaseOrderItemController.getItemsByMaterialId GET /material/{materialId}",

            // What has been requisitioned, so the store knows what a delivery answers and what is
            // still outstanding. Raising a requisition is not on this list. Both getAllIndents
            // overloads are here by name and by mapping: they are two endpoints, not one.
            "IndentControllerWeb.getAllIndents GET /all",
            "IndentControllerWeb.getAllIndentSummaries GET /summary",
            "IndentControllerWeb.getAllIndents GET",
            "IndentControllerWeb.getAnIndent GET /{id}",
            "IndentControllerWeb.getItems GET /{indentId}/items",
            "IndentItemControllerWeb.getIndentItemById GET /{id}",
            "IndentItemControllerWeb.getAllIndentItems GET",
            "IndentItemControllerWeb.getIndentItemsPaginated GET /paginated",
            "IndentItemControllerWeb.getIndentItemsByIndentId GET /indent/{indentId}",
            "IndentItemControllerWeb.getIndentItemsByMaterialId GET /material/{materialId}",
            "IndentItemControllerWeb.getIndentItemsByConversionStatus GET /conversion-status",

            // Who brought the delivery, and how to reach them about it. The vendor summary, bank
            // accounts, tax identifiers and payment terms are commercial rather than stores
            // information and are deliberately absent.
            "VendorControllerWeb.getVendorById GET /{id}",
            "VendorControllerWeb.getAllVendors GET",
            "VendorControllerWeb.getAllVendorsPaginated GET /all",
            "VendorControllerWeb.searchVendors GET /search",
            "VendorControllerWeb.getContacts GET /{vendorId}/contacts"
    ));

    /**
     * One granted endpoint: the controller it sits on, the method, and the mapping above it.
     *
     * <p>The signature names the mapping as well as the method, because a method name alone is not
     * unique. {@code IndentControllerWeb} declares {@code getAllIndents} twice, once on
     * {@code @GetMapping("/all")} and once on the bare {@code @GetMapping}, and keying by name
     * collapsed the two into one entry: the set comparison then held with one of the pair
     * ungranted, which is the failure this test exists to catch. Ninety-five guarded mappings must
     * produce ninety-five signatures.
     */
    private record Granted(String controller, String method, String verb, String path) {
        String signature() {
            String endpoint = path.isEmpty() ? verb : verb + " " + path;
            return controller + "." + method + " " + endpoint;
        }
    }

    private static final Pattern PRE_AUTHORIZE = Pattern.compile("^\\s*@PreAuthorize\\((.*)\\)\\s*$");
    private static final Pattern MAPPING = Pattern.compile(
            "^\\s*@(Get|Post|Put|Patch|Delete)Mapping\\b\\s*(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?");
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
        String verb = null;
        String path = null;
        for (String line : lines) {
            Matcher pre = PRE_AUTHORIZE.matcher(line);
            if (pre.matches()) {
                carriesRole = pre.group(1).contains("'" + ROLE + "'");
                continue;
            }
            Matcher map = MAPPING.matcher(line);
            if (map.find()) {
                verb = map.group(1).toUpperCase();
                path = map.group(2) == null ? "" : map.group(2);
                continue;
            }
            Matcher method = METHOD.matcher(line);
            if (method.matches()) {
                if (carriesRole) {
                    granted.add(new Granted(controller, method.group(1),
                            verb == null ? "?" : verb, path == null ? "" : path));
                }
                carriesRole = false;
                verb = null;
                path = null;
            }
        }
        return granted;
    }

    @Test
    void theRoleReachesExactlyTheEndpointsTheStoresWorkflowNeeds() throws IOException {
        List<Granted> granted = grantedEndpoints();
        Set<String> actual = new TreeSet<>();
        for (Granted g : granted) {
            actual.add(g.signature());
        }

        // The comparison below is a set comparison, so it is only as good as the key. Two guarded
        // mappings sharing a signature would collapse into one entry and the set could then hold
        // with one of the pair ungranted. Keying by mapping as well as method name is what keeps
        // them apart, and this is the assertion that says so rather than assuming it.
        assertThat(actual)
                .as("one signature per guarded mapping. A collapse here means the key is not "
                        + "unique and the comparison below can no longer see a removal")
                .hasSize(granted.size());

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
                    || method.startsWith("delete") || "DELETE".equals(g.verb())) {
                forbidden.add(g.signature());
            }
        }

        assertThat(forbidden)
                .as("endpoints on the '%s' role that approve, reject or delete", ROLE)
                .isEmpty();
    }
}

package org.tornotron.echno_backend.purchaseOrder;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.history.dto.StatusTransitionDto;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderCreationDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderUpdateDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.util.List;

@RestController
@RequestMapping("/api/v1/purchase-orders/web")
@Validated
@Tag(
        name = "Purchase Orders (Web)",
        description = "Web-console mirror of the purchase order endpoints. Same purchase order lifecycle "
                + "as the mobile API, but gated by the web app's org-role check instead of point "
                + "authorities."
)
public class PurchaseOrderControllerWeb {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderControllerWeb(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Create a purchase order",
            description = "Creates a purchase order for a vendor, with an optional link back to the indent "
                    + "it was raised from, and its line items in one call."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Purchase order created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A required field is missing or failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<PurchaseOrderDto> createPurchaseOrder(@Valid @RequestBody PurchaseOrderCreationDto creationDto) {
        PurchaseOrderDto created = purchaseOrderService.createPurchaseOrder(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Get a purchase order by id",
            description = "Returns a single purchase order including its vendor, indent link, items and totals."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No purchase order with the given id")
    })
    public ResponseEntity<PurchaseOrderDto> getPurchaseOrderById(@PathVariable Long id) {
        PurchaseOrderDto purchaseOrder = purchaseOrderService.getPurchaseOrderById(id);
        return ResponseEntity.ok(purchaseOrder);
    }

    /**
     * Reads a purchase order's status trail.
     *
     * @param id        The ID of the purchase order whose trail to read.
     * @param pageQuery The page bounds.
     * @return A {@link ResponseEntity} containing a page of trail entries and HTTP status 200 (OK).
     */
    @GetMapping("/{id}/status-history")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Read a purchase order's status trail",
            description = "Returns a page of the order's status entries, newest first: what it moved "
                    + "from, what it moved to, when, and by whom. An entry sourced SYSTEM names no "
                    + "person because none decided it: the order reached PARTIALLY_RECEIVED or "
                    + "FULLY_RECEIVED because the quantities received against it said so, and the "
                    + "note on the entry names the goods receipt that last moved it. Follow that "
                    + "receipt to find who filed it. Entries begin where recording began, so an order "
                    + "raised before the trail existed carries a single BASELINE entry naming the "
                    + "status it was observed to hold at that moment."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of status entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No purchase order with the given id")
    })
    public ResponseEntity<Page<StatusTransitionDto>> readStatusHistory(
            @PathVariable Long id,
            @Valid @ParameterObject PageQuery pageQuery) {
        return new ResponseEntity<>(
                purchaseOrderService.getStatusHistory(id, pageQuery.getPageNo(), pageQuery.pageSizeOr(20)),
                HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List all purchase orders",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase orders returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<PurchaseOrderDto>> getAllPurchaseOrders() {
        return UnpagedResultCap.respond(
                purchaseOrderService.getAllPurchaseOrders(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List purchase orders, paginated",
            description = "Returns a single page of purchase orders. The pageNo and pageSize parameters "
                    + "control paging."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of purchase orders returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<PurchaseOrderDto>> getAllPurchaseOrdersPaginated(
            @Valid @ParameterObject PageQuery pageQuery
    ) {
        Page<PurchaseOrderDto> purchaseOrders = purchaseOrderService.getAllPurchaseOrders(pageQuery.getPageNo(), pageQuery.getPageSize());
        return ResponseEntity.ok(purchaseOrders);
    }

    @GetMapping("/vendor/{vendorId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List purchase orders for a vendor",
            description = "Returns every purchase order raised against the given vendor, for example every "
                    + "PO placed with Sri Balaji Steel Traders."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase orders returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<PurchaseOrderDto>> getPurchaseOrdersByVendor(@PathVariable Long vendorId) {
        List<PurchaseOrderDto> purchaseOrders = purchaseOrderService.getPurchaseOrdersByVendor(vendorId);
        return ResponseEntity.ok(purchaseOrders);
    }

    @GetMapping("/indent/{indentId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List purchase orders for an indent",
            description = "Returns every purchase order that was raised from the given material indent."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase orders returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<PurchaseOrderDto>> getPurchaseOrdersByIndent(@PathVariable Long indentId) {
        List<PurchaseOrderDto> purchaseOrders = purchaseOrderService.getPurchaseOrdersByIndent(indentId);
        return ResponseEntity.ok(purchaseOrders);
    }

    @GetMapping("/status/{status}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List purchase orders by status",
            description = "Returns every purchase order currently in the given lifecycle status, such as "
                    + "SENT_TO_VENDOR or FULLY_RECEIVED."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase orders returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "status is not a recognised purchase order status"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<PurchaseOrderDto>> getPurchaseOrdersByStatus(@PathVariable PurchaseOrderStatus status) {
        List<PurchaseOrderDto> purchaseOrders = purchaseOrderService.getPurchaseOrdersByStatus(status);
        return ResponseEntity.ok(purchaseOrders);
    }

    @PatchMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Update a purchase order",
            description = "Applies a partial update to a purchase order's status, project, expected "
                    + "delivery date or remarks. The total is the sum of the line items and is "
                    + "recomputed whenever one of them changes, so it is not settable here."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No purchase order with the given id, or the payload names a project that does not exist in this organization")
    })
    public ResponseEntity<PurchaseOrderDto> updatePurchaseOrder(@Valid @RequestBody PurchaseOrderUpdateDto updateDto) {
        PurchaseOrderDto updated = purchaseOrderService.updatePurchaseOrder(updateDto);
        return ResponseEntity.ok(updated);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Update a purchase order's status",
            description = "Transitions a purchase order to the given status, for example from APPROVED to "
                    + "SENT_TO_VENDOR."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order status updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No purchase order with the given id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The requested transition is not valid from the purchase order's current status, for example cancelling an already fully received order")
    })
    public ResponseEntity<ApiResponse> updatePurchaseOrderStatus(
            @PathVariable Long id,
            @RequestParam PurchaseOrderStatus status
    ) {
        purchaseOrderService.updatePurchaseOrderStatus(id, status);
        return ResponseEntity.ok(new ApiResponse("Purchase Order status updated successfully"));
    }
}

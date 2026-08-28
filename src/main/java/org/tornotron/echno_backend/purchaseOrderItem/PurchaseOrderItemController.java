package org.tornotron.echno_backend.purchaseOrderItem;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemResponseDto;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemUpdateDto;

import java.util.List;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

@RestController
@RequestMapping("/api/v1/purchase-order-items")
@Validated
@Tag(
        name = "Purchase Order Items",
        description = "Line items belonging to a purchase order: the material, ordered and received "
                + "quantities, unit price and totals. Items can be created independently of the parent "
                + "order create call and looked up by purchase order or by material. Access is restricted "
                + "to the system-admin role for the current tenant."
)
public class PurchaseOrderItemController {

    private final PurchaseOrderItemService purchaseOrderItemService;

    public PurchaseOrderItemController(PurchaseOrderItemService purchaseOrderItemService) {
        this.purchaseOrderItemService = purchaseOrderItemService;
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping
    @Operation(
            summary = "Create a purchase order item",
            description = "Adds a line item to an existing purchase order, naming the material, ordered "
                    + "quantity and unit price."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Purchase order item created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A required field is missing or failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<PurchaseOrderItemResponseDto> createPurchaseOrderItem(
            @Valid @RequestBody PurchaseOrderItemCreationDto creationDto) {
        PurchaseOrderItemResponseDto created = purchaseOrderItemService.createPurchaseOrderItem(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/{id}")
    @Operation(
            summary = "Get a purchase order item by id",
            description = "Returns a single purchase order line item, including ordered and received quantities."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order item found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No purchase order item with the given id")
    })
    public ResponseEntity<PurchaseOrderItemResponseDto> getPurchaseOrderItemById(@PathVariable Long id) {
        PurchaseOrderItemResponseDto item = purchaseOrderItemService.getPurchaseOrderItemById(id);
        return ResponseEntity.ok(item);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping
    @Operation(
            summary = "List all purchase order items",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<PurchaseOrderItemResponseDto>> getAllPurchaseOrderItems() {
        return UnpagedResultCap.respond(
                purchaseOrderItemService.getAllPurchaseOrderItems(UnpagedResultCap.firstPage()));
    }

    /**
     * Retrieves a page of purchase order items, chosen by the caller.
     *
     * <p>The counterpart to the capped listing above. That one answers with the first
     * {@link UnpagedResultCap#MAX_ROWS} rows and marks itself when it left some out, but gives a
     * caller no way to ask for the rest. Here the {@link Page} reaches the response, so
     * {@code totalElements}, {@code totalPages} and the page index travel with the content.
     *
     * @param pageNo   Zero-based page index.
     * @param pageSize Rows per page, clamped to the result cap.
     * @return A {@link ResponseEntity} containing the page of purchase order items.
     */
    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List purchase order items, paginated",
            description = "Returns a single page of purchase order items with the paging metadata included. "
                    + "pageSize is clamped to 500."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of purchase order items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<PurchaseOrderItemResponseDto>> getPurchaseOrderItemsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(purchaseOrderItemService.getPurchaseOrderItemsPaginated(pageNo, pageSize));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/purchase-order/{purchaseOrderId}")
    @Operation(
            summary = "List items for a purchase order",
            description = "Returns every line item belonging to the given purchase order, for example all "
                    + "items on PO-2026-0042."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<PurchaseOrderItemResponseDto>> getItemsByPurchaseOrderId(
            @PathVariable Long purchaseOrderId) {
        List<PurchaseOrderItemResponseDto> items = purchaseOrderItemService.getItemsByPurchaseOrderId(purchaseOrderId);
        return ResponseEntity.ok(items);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/material/{materialId}")
    @Operation(
            summary = "List items for a material",
            description = "Returns every purchase order line item that orders the given material, across "
                    + "all purchase orders."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<PurchaseOrderItemResponseDto>> getItemsByMaterialId(@PathVariable Long materialId) {
        List<PurchaseOrderItemResponseDto> items = purchaseOrderItemService.getItemsByMaterialId(materialId);
        return ResponseEntity.ok(items);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PutMapping
    @Operation(
            summary = "Update a purchase order item",
            description = "Replaces a line item's ordered quantity, unit price and remarks."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order item updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No purchase order item with the given id")
    })
    public ResponseEntity<PurchaseOrderItemResponseDto> updatePurchaseOrderItem(
            @Valid @RequestBody PurchaseOrderItemUpdateDto updateDto) {
        PurchaseOrderItemResponseDto updated = purchaseOrderItemService.updatePurchaseOrderItem(updateDto);
        return ResponseEntity.ok(updated);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete a purchase order item",
            description = "Deletes the purchase order line item with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order item deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No purchase order item with the given id")
    })
    public ResponseEntity<ApiResponse> deletePurchaseOrderItem(@PathVariable Long id) {
        purchaseOrderItemService.deletePurchaseOrderItem(id);
        return ResponseEntity.ok(new ApiResponse("PurchaseOrderItem with id: " + id + " deleted successfully"));
    }
}

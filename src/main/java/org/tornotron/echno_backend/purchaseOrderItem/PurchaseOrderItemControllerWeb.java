package org.tornotron.echno_backend.purchaseOrderItem;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemResponseDto;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemUpdateDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/purchase-order-items/web")
@Tag(
        name = "Purchase Order Items (Web)",
        description = "Web-console mirror of the purchase order item endpoints. Same line-item operations "
                + "as the mobile API, gated by the web app's org-role check instead of point authorities."
)
public class PurchaseOrderItemControllerWeb {

    private final PurchaseOrderItemService purchaseOrderItemService;

    public PurchaseOrderItemControllerWeb(PurchaseOrderItemService purchaseOrderItemService) {
        this.purchaseOrderItemService = purchaseOrderItemService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
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

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
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

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List all purchase order items",
            description = "Returns every purchase order line item for the current tenant, unpaginated."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Purchase order items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<PurchaseOrderItemResponseDto>> getAllPurchaseOrderItems() {
        List<PurchaseOrderItemResponseDto> items = purchaseOrderItemService.getAllPurchaseOrderItems();
        return ResponseEntity.ok(items);
    }

    @GetMapping("/purchase-order/{purchaseOrderId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
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

    @GetMapping("/material/{materialId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
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

    @PatchMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
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

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
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

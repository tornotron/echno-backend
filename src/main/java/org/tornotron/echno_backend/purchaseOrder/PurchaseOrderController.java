package org.tornotron.echno_backend.purchaseOrder;

import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderCreationDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderUpdateDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;

import java.util.List;

@RestController
@RequestMapping("/api/v1/purchase-orders")
@Validated
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping
    public ResponseEntity<PurchaseOrderDto> createPurchaseOrder(@Valid @RequestBody PurchaseOrderCreationDto creationDto) {
        PurchaseOrderDto created = purchaseOrderService.createPurchaseOrder(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/{id}")
    public ResponseEntity<PurchaseOrderDto> getPurchaseOrderById(@PathVariable Long id) {
        PurchaseOrderDto purchaseOrder = purchaseOrderService.getPurchaseOrderById(id);
        return ResponseEntity.ok(purchaseOrder);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping
    public ResponseEntity<List<PurchaseOrderDto>> getAllPurchaseOrders() {
        List<PurchaseOrderDto> purchaseOrders = purchaseOrderService.getAllPurchaseOrders();
        return ResponseEntity.ok(purchaseOrders);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/all")
    public ResponseEntity<Page<PurchaseOrderDto>> getAllPurchaseOrdersPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<PurchaseOrderDto> purchaseOrders = purchaseOrderService.getAllPurchaseOrders(pageNo, pageSize);
        return ResponseEntity.ok(purchaseOrders);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/vendor/{vendorId}")
    public ResponseEntity<List<PurchaseOrderDto>> getPurchaseOrdersByVendor(@PathVariable Long vendorId) {
        List<PurchaseOrderDto> purchaseOrders = purchaseOrderService.getPurchaseOrdersByVendor(vendorId);
        return ResponseEntity.ok(purchaseOrders);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/indent/{indentId}")
    public ResponseEntity<List<PurchaseOrderDto>> getPurchaseOrdersByIndent(@PathVariable Long indentId) {
        List<PurchaseOrderDto> purchaseOrders = purchaseOrderService.getPurchaseOrdersByIndent(indentId);
        return ResponseEntity.ok(purchaseOrders);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/status/{status}")
    public ResponseEntity<List<PurchaseOrderDto>> getPurchaseOrdersByStatus(@PathVariable PurchaseOrderStatus status) {
        List<PurchaseOrderDto> purchaseOrders = purchaseOrderService.getPurchaseOrdersByStatus(status);
        return ResponseEntity.ok(purchaseOrders);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PatchMapping
    public ResponseEntity<PurchaseOrderDto> updatePurchaseOrder(@Valid @RequestBody PurchaseOrderUpdateDto updateDto) {
        PurchaseOrderDto updated = purchaseOrderService.updatePurchaseOrder(updateDto);
        return ResponseEntity.ok(updated);
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse> updatePurchaseOrderStatus(
            @PathVariable Long id,
            @RequestParam PurchaseOrderStatus status
    ) {
        purchaseOrderService.updatePurchaseOrderStatus(id, status);
        return ResponseEntity.ok(new ApiResponse("Purchase Order status updated successfully"));
    }
}

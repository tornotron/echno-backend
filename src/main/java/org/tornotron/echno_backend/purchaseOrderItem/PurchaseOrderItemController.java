package org.tornotron.echno_backend.purchaseOrderItem;

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

@RestController
@RequestMapping("/api/v1/purchase-order-items")
@Validated
public class PurchaseOrderItemController {

    private final PurchaseOrderItemService purchaseOrderItemService;

    public PurchaseOrderItemController(PurchaseOrderItemService purchaseOrderItemService) {
        this.purchaseOrderItemService = purchaseOrderItemService;
    }

    @PostMapping
    public ResponseEntity<PurchaseOrderItemResponseDto> createPurchaseOrderItem(
            @Valid @RequestBody PurchaseOrderItemCreationDto creationDto) {
        PurchaseOrderItemResponseDto created = purchaseOrderItemService.createPurchaseOrderItem(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurchaseOrderItemResponseDto> getPurchaseOrderItemById(@PathVariable Long id) {
        PurchaseOrderItemResponseDto item = purchaseOrderItemService.getPurchaseOrderItemById(id);
        return ResponseEntity.ok(item);
    }

    @GetMapping
    public ResponseEntity<List<PurchaseOrderItemResponseDto>> getAllPurchaseOrderItems() {
        List<PurchaseOrderItemResponseDto> items = purchaseOrderItemService.getAllPurchaseOrderItems();
        return ResponseEntity.ok(items);
    }

    @GetMapping("/purchase-order/{purchaseOrderId}")
    public ResponseEntity<List<PurchaseOrderItemResponseDto>> getItemsByPurchaseOrderId(
            @PathVariable Long purchaseOrderId) {
        List<PurchaseOrderItemResponseDto> items = purchaseOrderItemService.getItemsByPurchaseOrderId(purchaseOrderId);
        return ResponseEntity.ok(items);
    }

    @GetMapping("/material/{materialId}")
    public ResponseEntity<List<PurchaseOrderItemResponseDto>> getItemsByMaterialId(@PathVariable Long materialId) {
        List<PurchaseOrderItemResponseDto> items = purchaseOrderItemService.getItemsByMaterialId(materialId);
        return ResponseEntity.ok(items);
    }

    @PutMapping
    public ResponseEntity<PurchaseOrderItemResponseDto> updatePurchaseOrderItem(
            @Valid @RequestBody PurchaseOrderItemUpdateDto updateDto) {
        PurchaseOrderItemResponseDto updated = purchaseOrderItemService.updatePurchaseOrderItem(updateDto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deletePurchaseOrderItem(@PathVariable Long id) {
        purchaseOrderItemService.deletePurchaseOrderItem(id);
        return ResponseEntity.ok(new ApiResponse("PurchaseOrderItem with id: " + id + " deleted successfully"));
    }
}

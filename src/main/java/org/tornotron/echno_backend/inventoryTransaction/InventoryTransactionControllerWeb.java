package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryMaterialStockDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory-transactions/web")
public class InventoryTransactionControllerWeb {
    private final InventoryTransactionService inventoryTransactionService;
    private final InventoryService inventoryService;

    public InventoryTransactionControllerWeb(InventoryTransactionService inventoryTransactionService,
                                             InventoryService inventoryService) {
        this.inventoryTransactionService = inventoryTransactionService;
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<InventoryTransactionDto> getTransactionById(@PathVariable Long id) {
        InventoryTransactionDto transaction = inventoryTransactionService.getTransactionById(id);
        return ResponseEntity.ok(transaction);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getAllTransactions() {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getAllTransactions();
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<Page<InventoryTransactionDto>> getAllTransactionsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<InventoryTransactionDto> transactions = inventoryTransactionService.getAllTransactions(pageNo, pageSize);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/material/{materialId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByMaterial(@PathVariable Long materialId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByMaterial(materialId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByProject(@PathVariable Long projectId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByProject(projectId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/type/{transactionType}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByType(@PathVariable InventoryTransactionType transactionType) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByType(transactionType);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/date-range")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByDateRange(startDate, endDate);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/storage-location/{storageLocationId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByStorageLocation(
            @PathVariable Long storageLocationId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByStorageLocation(storageLocationId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/storage-location/{storageLocationId}/stock")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<InventoryMaterialStockDto> getStockByStorageLocation(
            @PathVariable Long storageLocationId) {
        InventoryMaterialStockDto stock = inventoryService.getStockByStorageLocation(storageLocationId);
        return ResponseEntity.ok(stock);
    }
}

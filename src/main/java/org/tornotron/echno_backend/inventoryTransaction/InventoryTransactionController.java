package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory-transactions")
@Validated
public class InventoryTransactionController {

    private final InventoryTransactionService inventoryTransactionService;

    public InventoryTransactionController(InventoryTransactionService inventoryTransactionService) {
        this.inventoryTransactionService = inventoryTransactionService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    public ResponseEntity<InventoryTransactionDto> getTransactionById(@PathVariable Long id) {
        InventoryTransactionDto transaction = inventoryTransactionService.getTransactionById(id);
        return ResponseEntity.ok(transaction);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getAllTransactions() {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getAllTransactions();
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    public ResponseEntity<Page<InventoryTransactionDto>> getAllTransactionsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<InventoryTransactionDto> transactions = inventoryTransactionService.getAllTransactions(pageNo, pageSize);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/material/{materialId}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByMaterial(@PathVariable Long materialId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByMaterial(materialId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByProject(@PathVariable Long projectId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByProject(projectId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/type/{transactionType}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByType(@PathVariable InventoryTransactionType transactionType) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByType(transactionType);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/date-range")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByDateRange(startDate, endDate);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/storage-location/{storageLocationId}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByStorageLocation(
            @PathVariable Long storageLocationId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByStorageLocation(storageLocationId);
        return ResponseEntity.ok(transactions);
    }
}

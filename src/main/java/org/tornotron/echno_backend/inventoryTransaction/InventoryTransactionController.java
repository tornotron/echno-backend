package org.tornotron.echno_backend.inventoryTransaction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryMaterialStockDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.MaterialLocationStockDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.TaskMaterialUsageDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory-transactions")
@Validated
@Tag(
        name = "Inventory Transactions",
        description = "Read access to the inventory transaction ledger and derived stock positions. "
                + "Every stock movement (goods receipt, consumption, transfer between storage locations, "
                + "adjustment or opening balance) is recorded as a transaction that carries the opening, "
                + "changed and closing quantity for a material. These endpoints query the ledger by "
                + "material, project, task, storage location, transaction type or date range, and roll it "
                + "up into current stock by location and material. All endpoints require the "
                + "inventory-transaction read or admin authority."
)
public class InventoryTransactionController {

    private final InventoryTransactionService inventoryTransactionService;
    private final InventoryService inventoryService;

    public InventoryTransactionController(InventoryTransactionService inventoryTransactionService,
                                          InventoryService inventoryService) {
        this.inventoryTransactionService = inventoryTransactionService;
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "Get an inventory transaction by id",
            description = "Returns a single inventory transaction, including its opening, changed and "
                    + "closing quantities and the material, project, task and storage location it applies to."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority"),
            @ApiResponse(responseCode = "404", description = "No transaction with the given id")
    })
    public ResponseEntity<InventoryTransactionDto> getTransactionById(@PathVariable Long id) {
        InventoryTransactionDto transaction = inventoryTransactionService.getTransactionById(id);
        return ResponseEntity.ok(transaction);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "List all inventory transactions",
            description = "Returns every inventory transaction as an unpaged list. Use the paginated "
                    + "variant for large ledgers."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of transactions"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getAllTransactions() {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getAllTransactions();
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "List inventory transactions (paginated)",
            description = "Returns a page of inventory transactions ordered by the service default. "
                    + "The pageNo and pageSize parameters control the slice returned."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of transactions"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<Page<InventoryTransactionDto>> getAllTransactionsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<InventoryTransactionDto> transactions = inventoryTransactionService.getAllTransactions(pageNo, pageSize);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/material/{materialId}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "List transactions for a material",
            description = "Returns every stock movement recorded for the given material across all "
                    + "projects and storage locations."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of transactions for the material"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByMaterial(@PathVariable Long materialId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByMaterial(materialId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "List transactions for a project",
            description = "Returns every inventory transaction booked against the given project."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of transactions for the project"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByProject(@PathVariable Long projectId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByProject(projectId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/type/{transactionType}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "List transactions by type",
            description = "Returns transactions of a single movement type, for example GRN (goods received), "
                    + "USE (consumption), TRANSFER_OUT, TRANSFER_IN, ADJUST or OPENING_BALANCE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of transactions of the given type"),
            @ApiResponse(responseCode = "400", description = "The path value is not a recognised transaction type"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByType(@PathVariable InventoryTransactionType transactionType) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByType(transactionType);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/date-range")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "List transactions in a date range",
            description = "Returns transactions whose transaction date falls between the startDate and "
                    + "endDate parameters (both ISO date-time values)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of transactions in the range"),
            @ApiResponse(responseCode = "400", description = "startDate or endDate is missing or not a valid ISO date-time"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByDateRange(startDate, endDate);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/storage-location/{storageLocationId}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "List transactions for a storage location",
            description = "Returns every stock movement recorded at the given storage location."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of transactions for the storage location"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByStorageLocation(
            @PathVariable Long storageLocationId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByStorageLocation(storageLocationId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/storage-location/{storageLocationId}/stock")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "Get current stock at a storage location",
            description = "Rolls up the transaction ledger into the current stock held at the given "
                    + "storage location, broken down by material with per-material quantity and value "
                    + "and the location totals."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current stock for the storage location"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<InventoryMaterialStockDto> getStockByStorageLocation(
            @PathVariable Long storageLocationId) {
        InventoryMaterialStockDto stock = inventoryService.getStockByStorageLocation(storageLocationId);
        return ResponseEntity.ok(stock);
    }

    @GetMapping("/material/{materialId}/stock")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "Get current stock for a material",
            description = "Rolls up the transaction ledger into the current stock of the given material, "
                    + "broken down by storage location with per-location quantity and value and the "
                    + "material totals."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current stock for the material"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<MaterialLocationStockDto> getStockByMaterial(
            @PathVariable Long materialId) {
        MaterialLocationStockDto stock = inventoryService.getStockByMaterial(materialId);
        return ResponseEntity.ok(stock);
    }

    @GetMapping("/task/{taskId}")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "List transactions for a task",
            description = "Returns the stock movements booked against the given task, typically the "
                    + "material consumed while working on it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of transactions for the task"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByTask(@PathVariable Long taskId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByTask(taskId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/project/{projectId}/task-summary")
    @PreAuthorize("hasAuthority('inventory-transaction:read') or hasAuthority('inventory-transaction:admin')")
    @Operation(
            summary = "Get material usage per task for a project",
            description = "Summarises consumption across the given project grouped by task: for each task "
                    + "the materials used with their quantity and cost, plus the task totals."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Per-task material usage summary for the project"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the inventory-transaction read or admin authority")
    })
    public ResponseEntity<List<TaskMaterialUsageDto>> getTaskMaterialUsageSummary(@PathVariable Long projectId) {
        List<TaskMaterialUsageDto> summary = inventoryTransactionService.getTaskMaterialUsageSummary(projectId);
        return ResponseEntity.ok(summary);
    }
}

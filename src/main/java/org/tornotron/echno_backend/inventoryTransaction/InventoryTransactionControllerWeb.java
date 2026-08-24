package org.tornotron.echno_backend.inventoryTransaction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryMaterialStockDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.MaterialLocationStockDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.MaterialMovementHistoryDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.TaskMaterialUsageDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory-transactions/web")
@Tag(
        name = "Inventory Transactions (Web)",
        description = "Movements of material stock, such as receipts, issues and usage, together with "
                + "derived stock levels by storage location or material. This is the web-console API, "
                + "restricted to the system-admin role for the current tenant. Endpoints are read-only "
                + "and cover fetching a transaction, browsing and filtering by material, project, type, "
                + "date range, storage location or task, and reading current stock and task usage totals."
)
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
    @Operation(
            summary = "Get an inventory transaction by id",
            description = "Returns a single inventory transaction."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inventory transaction found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No inventory transaction with the given id")
    })
    public ResponseEntity<InventoryTransactionDto> getTransactionById(@PathVariable Long id) {
        InventoryTransactionDto transaction = inventoryTransactionService.getTransactionById(id);
        return ResponseEntity.ok(transaction);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List inventory transactions",
            description = "Returns every inventory transaction in the current tenant."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inventory transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getAllTransactions() {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getAllTransactions();
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List inventory transactions (paged)",
            description = "Returns a single page of inventory transactions controlled by the pageNo and pageSize parameters."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of inventory transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<InventoryTransactionDto>> getAllTransactionsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<InventoryTransactionDto> transactions = inventoryTransactionService.getAllTransactions(pageNo, pageSize);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/material/{materialId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List inventory transactions for a material",
            description = "Returns the inventory transactions recorded for the given material."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inventory transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByMaterial(@PathVariable Long materialId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByMaterial(materialId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/material/{materialId}/history")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Get a material's movement history (timeline, paged)",
            description = "Returns the material's stock movements as a timeline, oldest movement first, "
                    + "one page at a time. Each entry carries the storage location, project, movement type "
                    + "and its stock direction, the quantity changed, the timestamp and the source reference "
                    + "so the caller can show where the material has been, when, and how much."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of movement-history entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<MaterialMovementHistoryDto>> getMaterialMovementHistory(
            @PathVariable Long materialId,
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<MaterialMovementHistoryDto> history = inventoryTransactionService.getMaterialMovementHistory(materialId, pageNo, pageSize);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List inventory transactions for a project",
            description = "Returns the inventory transactions recorded for the given project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inventory transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByProject(@PathVariable Long projectId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByProject(projectId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/type/{transactionType}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List inventory transactions by type",
            description = "Returns the inventory transactions of the given transaction type."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inventory transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The transaction type is not a recognized value"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByType(@PathVariable InventoryTransactionType transactionType) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByType(transactionType);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/date-range")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List inventory transactions by date range",
            description = "Returns the inventory transactions recorded between the given start and end date-times."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inventory transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A date parameter is missing or malformed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByDateRange(startDate, endDate);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/storage-location/{storageLocationId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List inventory transactions for a storage location",
            description = "Returns the inventory transactions recorded at the given storage location."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inventory transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByStorageLocation(
            @PathVariable Long storageLocationId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByStorageLocation(storageLocationId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/storage-location/{storageLocationId}/material/{materialId}/project/{projectId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List inventory transactions for a location, material and project",
            description = "Returns the inventory transactions matching the given storage location, material and project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inventory transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByStorageLocationMaterialAndProject(
            @PathVariable Long storageLocationId,
            @PathVariable Long materialId,
            @PathVariable Long projectId
    ) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByStorageLocationMaterialAndProject(storageLocationId,materialId,projectId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/storage-location/{storageLocationId}/stock")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Get stock at a storage location",
            description = "Returns the current material stock levels at the given storage location."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<InventoryMaterialStockDto> getStockByStorageLocation(
            @PathVariable Long storageLocationId) {
        InventoryMaterialStockDto stock = inventoryService.getStockByStorageLocation(storageLocationId);
        return ResponseEntity.ok(stock);
    }

    @GetMapping("/material/{materialId}/stock")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Get stock for a material",
            description = "Returns the current stock levels of the given material across storage locations."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<MaterialLocationStockDto> getStockByMaterial(
            @PathVariable Long materialId) {
        MaterialLocationStockDto stock = inventoryService.getStockByMaterial(materialId);
        return ResponseEntity.ok(stock);
    }

    @GetMapping("/task/{taskId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List inventory transactions for a task",
            description = "Returns the inventory transactions recorded for the given task."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inventory transactions returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<InventoryTransactionDto>> getTransactionsByTask(@PathVariable Long taskId) {
        List<InventoryTransactionDto> transactions = inventoryTransactionService.getTransactionsByTask(taskId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/project/{projectId}/task-summary")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Get task material usage summary for a project",
            description = "Returns a per-task summary of material usage for the given project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Task material usage summary returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<TaskMaterialUsageDto>> getTaskMaterialUsageSummary(@PathVariable Long projectId) {
        List<TaskMaterialUsageDto> summary = inventoryTransactionService.getTaskMaterialUsageSummary(projectId);
        return ResponseEntity.ok(summary);
    }

}

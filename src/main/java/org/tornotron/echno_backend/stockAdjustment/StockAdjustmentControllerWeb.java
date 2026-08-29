package org.tornotron.echno_backend.stockAdjustment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentCreationDto;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentDto;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/stock-adjustments/web")
@Tag(
        name = "Stock Adjustments (Web)",
        description = "Documents that correct the recorded on-hand quantity of materials at a storage "
                + "location against a physical count, capturing the variance, its reason and justification "
                + "per line item. This is the controlled way to set or correct a stock balance: approving "
                + "a document posts its lines to the inventory ledger and moves the balance, so the "
                + "resulting figure stays explainable. Endpoints cover creating, updating, browsing, "
                + "approving and deleting stock adjustment documents for the caller's current tenant. "
                + "Read endpoints require tenant membership; write and approval endpoints require the "
                + "system-admin or project-manager role, and approval additionally has to come from "
                + "someone other than whoever raised the document."
)
public class StockAdjustmentControllerWeb {

    private final StockAdjustmentService stockAdjustmentService;

    public StockAdjustmentControllerWeb(StockAdjustmentService stockAdjustmentService) {
        this.stockAdjustmentService = stockAdjustmentService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List all stock adjustments",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock adjustments returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it")
    })
    public ResponseEntity<List<StockAdjustmentDto>> readAllStockAdjustments() {
        return UnpagedResultCap.respond(stockAdjustmentService.getAll(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List stock adjustments, paginated",
            description = "Returns a single page of stock adjustments ordered by creation time, most recent "
                    + "first. The pageNo and pageSize parameters control paging."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of stock adjustments returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it")
    })
    public ResponseEntity<Page<StockAdjustmentDto>> readAllStockAdjustmentsPaginated(
            @Valid @ParameterObject PageQuery pageQuery) {
        return new ResponseEntity<>(stockAdjustmentService.getAll(pageQuery.getPageNo(), pageQuery.getPageSize()), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Get a stock adjustment by id",
            description = "Returns a single stock adjustment document, including its header fields and line "
                    + "items."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock adjustment found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No stock adjustment with the given id")
    })
    public ResponseEntity<StockAdjustmentDto> readAStockAdjustment(@PathVariable Long id) {
        return new ResponseEntity<>(stockAdjustmentService.getById(id), HttpStatus.OK);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Create a stock adjustment",
            description = "Records a stock adjustment document capturing the counted and system quantities, "
                    + "variance and justification for one or more materials at a location. The document is "
                    + "created as a draft: it does not change any stock balance until it is approved. "
                    + "The caller is recorded as the user who raised it, and cannot then approve it "
                    + "themselves unless they hold the system-admin role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Stock adjustment created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A required field is missing or failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "The referenced location or project, or a material on one of the line items, was not found")
    })
    public ResponseEntity<StockAdjustmentDto> createStockAdjustment(@Valid @RequestBody StockAdjustmentCreationDto creationDto) {
        return new ResponseEntity<>(stockAdjustmentService.create(creationDto), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Update a stock adjustment",
            description = "Replaces the header fields and line items of an existing stock adjustment with "
                    + "the given values. Refused once the adjustment has been approved and posted, since "
                    + "the ledger entries would then describe a document that no longer exists."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock adjustment updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A required field is missing or failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No stock adjustment with the given id, or the referenced location, project, or a material on one of the line items was not found")
    })
    public ResponseEntity<StockAdjustmentDto> updateStockAdjustment(@PathVariable Long id,
                                                                    @Valid @RequestBody StockAdjustmentCreationDto creationDto) {
        return new ResponseEntity<>(stockAdjustmentService.update(id, creationDto), HttpStatus.OK);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Approve a stock adjustment and post it to the stock ledger",
            description = "Approves the adjustment and posts each of its lines as an inventory "
                    + "transaction, then moves the balance for that material, project and storage "
                    + "location. A line carrying a counted physical quantity is posted as the movement "
                    + "needed to reach that count from the balance as it stands at approval; a line "
                    + "without one uses its signed adjustment quantity. Every line must carry a reason, "
                    + "or the document must give a primary reason to fall back on, because the reason is "
                    + "what the resulting balance is explained by. This is the only path that sets or "
                    + "corrects a balance, and it is restricted to the system-admin and project-manager "
                    + "roles. Whoever raised the document cannot approve it: an approval is the second "
                    + "pair of eyes on the movement it posts, so it has to come from someone else. A "
                    + "system administrator is the one exception, and their self-approval is recorded as "
                    + "one on the ledger entries. It runs once: an approved document is frozen against "
                    + "further posting, editing and deletion, and a mistake is corrected by raising "
                    + "another adjustment."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock adjustment approved and posted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The adjustment is already posted, is being approved by whoever raised it without the system-admin role, names no project, has no lines, or a line is missing a material, a reason or a quantity, or would take a balance below zero"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No stock adjustment with the given id")
    })
    public ResponseEntity<StockAdjustmentDto> approveStockAdjustment(@PathVariable Long id) {
        return new ResponseEntity<>(stockAdjustmentService.approve(id), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete a stock adjustment",
            description = "Deletes the stock adjustment with the given id. Refused once the adjustment "
                    + "has been approved and posted; raise a further adjustment instead."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock adjustment deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No stock adjustment with the given id")
    })
    public ResponseEntity<ApiResponse> deleteStockAdjustment(@PathVariable Long id) {
        stockAdjustmentService.delete(id);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Stock adjustment with id: " + id + " has been deleted"));
    }
}

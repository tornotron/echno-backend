package org.tornotron.echno_backend.stockAdjustment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentCreationDto;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentDto;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/stock-adjustments/web")
@Tag(
        name = "Stock Adjustments (Web)",
        description = "Documents that correct the recorded on-hand quantity of materials at a storage "
                + "location against a physical count, capturing the variance, its reason and justification "
                + "per line item. Endpoints cover creating, updating, browsing and deleting stock "
                + "adjustment documents for the caller's current tenant. Read endpoints require tenant "
                + "membership; write endpoints require the system-admin or project-manager role."
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
            description = "Returns every stock adjustment document recorded for the organization, without "
                    + "pagination."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stock adjustments returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it")
    })
    public ResponseEntity<List<StockAdjustmentDto>> readAllStockAdjustments() {
        return new ResponseEntity<>(stockAdjustmentService.getAll(), HttpStatus.OK);
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
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        return new ResponseEntity<>(stockAdjustmentService.getAll(pageNo, pageSize), HttpStatus.OK);
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
                    + "variance and justification for one or more materials at a location. In the current "
                    + "scope the document is persisted as a record only; it does not yet post inventory "
                    + "transactions or change the current stock balance."
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
                    + "the given values."
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

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete a stock adjustment",
            description = "Deletes the stock adjustment with the given id."
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

package org.tornotron.echno_backend.stockAdjustment;

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
public class StockAdjustmentControllerWeb {

    private final StockAdjustmentService stockAdjustmentService;

    public StockAdjustmentControllerWeb(StockAdjustmentService stockAdjustmentService) {
        this.stockAdjustmentService = stockAdjustmentService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<List<StockAdjustmentDto>> readAllStockAdjustments() {
        return new ResponseEntity<>(stockAdjustmentService.getAll(), HttpStatus.OK);
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<Page<StockAdjustmentDto>> readAllStockAdjustmentsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        return new ResponseEntity<>(stockAdjustmentService.getAll(pageNo, pageSize), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<StockAdjustmentDto> readAStockAdjustment(@PathVariable Long id) {
        return new ResponseEntity<>(stockAdjustmentService.getById(id), HttpStatus.OK);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<StockAdjustmentDto> createStockAdjustment(@Valid @RequestBody StockAdjustmentCreationDto creationDto) {
        return new ResponseEntity<>(stockAdjustmentService.create(creationDto), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<StockAdjustmentDto> updateStockAdjustment(@PathVariable Long id,
                                                                    @Valid @RequestBody StockAdjustmentCreationDto creationDto) {
        return new ResponseEntity<>(stockAdjustmentService.update(id, creationDto), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<ApiResponse> deleteStockAdjustment(@PathVariable Long id) {
        stockAdjustmentService.delete(id);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Stock adjustment with id: " + id + " has been deleted"));
    }
}

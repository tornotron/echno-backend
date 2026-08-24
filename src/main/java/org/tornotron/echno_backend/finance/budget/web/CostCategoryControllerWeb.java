package org.tornotron.echno_backend.finance.budget.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.budget.dtos.CostCategoryDto;
import org.tornotron.echno_backend.finance.budget.dtos.CreateCostCategoryRequest;
import org.tornotron.echno_backend.finance.budget.dtos.UpdateCostCategoryRequest;
import org.tornotron.echno_backend.finance.budget.service.CostCategoryService;
import org.tornotron.echno_backend.finance.budget.service.CostCategorySeeder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/cost-categories/web")
@RequiredArgsConstructor
@Tag(
        name = "Cost Categories",
        description = "Budget heads (cost categories) that a project budget is broken down by and that "
                + "construction invoice lines are tagged to. An org-level master list, optionally aligned "
                + "to a ledger expense account. All endpoints are tenant scoped and limited to system "
                + "administrators and project managers, apart from the default seed, which is admin only."
)
public class CostCategoryControllerWeb {

    private final CostCategoryService service;
    private final CostCategorySeeder seeder;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List cost categories",
            description = "Returns the budget heads in the current tenant. By default only active heads are "
                    + "returned; set activeOnly to false to include deactivated ones."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of cost categories"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<CostCategoryDto> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return service.findAll(activeOnly);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get a cost category by id",
            description = "Returns a single budget head by its unique id."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cost category found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No cost category with the given id in the current tenant")
    })
    public CostCategoryDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Create a cost category",
            description = "Creates a budget head. The name must be unique within the tenant; an optional "
                    + "expense account maps the head to the chart of accounts for reporting."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cost category created"),
            @ApiResponse(responseCode = "400", description = "Validation failed or the name is already in use"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "The referenced expense account does not exist")
    })
    public ResponseEntity<CostCategoryDto> create(@Valid @RequestBody CreateCostCategoryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Update a cost category",
            description = "Edits a budget head's name, code, expense-account mapping and active flag. The "
                    + "name must stay unique within the tenant; omit the expense account id to clear the mapping."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cost category updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed or the name clashes"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No cost category, or no such expense account, in the current tenant")
    })
    public CostCategoryDto update(@PathVariable UUID id, @Valid @RequestBody UpdateCostCategoryRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Deactivate a cost category",
            description = "Marks a budget head inactive so it can no longer be tagged on new invoice lines "
                    + "or allocated to. Existing tagged lines and allocations are left unchanged."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cost category deactivated"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No cost category with the given id in the current tenant")
    })
    public CostCategoryDto deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    @PostMapping("/seed-defaults")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Seed the default cost categories",
            description = "Creates a default set of budget heads (Materials, Subcontractor, Labour, Plant "
                    + "and Equipment, Other) mapped to the seeded expense accounts. The operation is "
                    + "idempotent: a tenant that already has any cost category is left untouched. Returns "
                    + "the number of categories created. Restricted to system administrators."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seed complete, returns the count of categories created"),
            @ApiResponse(responseCode = "403", description = "Caller is not a system administrator in the current tenant")
    })
    public Map<String, Integer> seedDefaults() {
        return Map.of("created", seeder.seedDefaults());
    }
}

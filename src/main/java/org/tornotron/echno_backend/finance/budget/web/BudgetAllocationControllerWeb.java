package org.tornotron.echno_backend.finance.budget.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.budget.dtos.BudgetAllocationDto;
import org.tornotron.echno_backend.finance.budget.dtos.UpsertBudgetAllocationRequest;
import org.tornotron.echno_backend.finance.budget.service.BudgetAllocationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/projects/{projectId}/budget/web")
@RequiredArgsConstructor
@Tag(
        name = "Project Budget",
        description = "A project's budget, expressed as one allocation per budget head (cost category). "
                + "All endpoints are tenant scoped and limited to system administrators and project managers."
)
public class BudgetAllocationControllerWeb {

    private final BudgetAllocationService service;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List a project's budget allocations",
            description = "Returns the set of budget-head allocations that make up the project's budget."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of budget allocations"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in the current tenant")
    })
    public List<BudgetAllocationDto> list(@PathVariable Long projectId) {
        return service.findByProject(projectId);
    }

    @PutMapping("/{costCategoryId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Set the allocation for a budget head",
            description = "Sets the amount allocated to a cost category on the project. Creates the "
                    + "allocation on first use and replaces the amount thereafter, so the call is idempotent "
                    + "for a given project and category."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Allocation created or updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such project or cost category in the current tenant")
    })
    public BudgetAllocationDto upsert(@PathVariable Long projectId,
                                      @PathVariable UUID costCategoryId,
                                      @Valid @RequestBody UpsertBudgetAllocationRequest req) {
        return service.upsert(projectId, costCategoryId, req);
    }

    @DeleteMapping("/{costCategoryId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Remove a budget-head allocation",
            description = "Deletes the allocation for a cost category on the project, taking that head out "
                    + "of the project's budget."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Allocation deleted"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such project, or no allocation for the category, in the current tenant")
    })
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable UUID costCategoryId) {
        service.delete(projectId, costCategoryId);
        return ResponseEntity.noContent().build();
    }
}

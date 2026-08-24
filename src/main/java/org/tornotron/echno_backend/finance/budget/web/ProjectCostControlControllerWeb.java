package org.tornotron.echno_backend.finance.budget.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.finance.budget.dtos.ProjectCostControlDto;
import org.tornotron.echno_backend.finance.budget.service.ProjectCostControlService;

@RestController
@RequestMapping("/api/v1/finance/projects/{projectId}/cost-control/web")
@RequiredArgsConstructor
@Tag(
        name = "Project Cost Control",
        description = "The project cost-control view: per budget head, the amount allocated against what has "
                + "been committed (approved but not fully paid) and spent (fully paid), and what remains, with "
                + "over-budget heads flagged. Tenant scoped and limited to system administrators and project managers."
)
public class ProjectCostControlControllerWeb {

    private final ProjectCostControlService service;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get the cost-control view for a project",
            description = "Returns the per-budget-head roll-up of allocated, committed, spent and remaining "
                    + "amounts for the project, plus a project total row. Heads with an allocation or any "
                    + "tagged spend are included; a head whose committed plus spent exceeds its allocation is "
                    + "flagged as over budget."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cost-control view for the project"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in the current tenant")
    })
    public ProjectCostControlDto get(@PathVariable Long projectId) {
        return service.getForProject(projectId);
    }
}

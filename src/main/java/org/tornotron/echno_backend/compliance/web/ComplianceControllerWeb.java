package org.tornotron.echno_backend.compliance.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.compliance.ComplianceGenerationService;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;

import java.util.List;

/**
 * Manual entry point for AI compliance generation. The automatic path runs on project
 * approval through {@code ComplianceGenerationListener}; this lets a project manager
 * re-run generation on demand (for example after the AI key is configured, or after
 * new rules are added). Generation is idempotent, so a re-run only adds compliances
 * that do not already exist. Runs synchronously and returns the rows it created.
 */
@RestController
@RequestMapping("/api/v1/inspections/web/compliance")
@RequiredArgsConstructor
@Tag(
        name = "Compliance Generation",
        description = "Manual trigger for AI-driven compliance inspection generation. A project's "
                + "location and type are matched against curated compliance rules and an OpenAI-compatible "
                + "inference endpoint to create compliance-type inspections; this endpoint re-runs that generation on "
                + "demand, in addition to the automatic run on project approval."
)
public class ComplianceControllerWeb {

    private final ComplianceGenerationService complianceGenerationService;

    @PostMapping("/regenerate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Regenerate compliance inspections for a project",
            description = "Runs compliance generation for the given project synchronously and returns the "
                    + "compliance inspections it created. Idempotent: inspections that already exist for a "
                    + "matched rule are not duplicated."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Generation ran; the created inspections are returned (possibly empty)"),
            @ApiResponse(responseCode = "400", description = "No organization context set: the X-Organization-Id header is required"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in the current tenant")
    })
    public List<InspectionDto> regenerate(@RequestParam Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            throw new TenantIdMissingException("No organization context set; the X-Organization-Id header is required");
        }
        return complianceGenerationService.generateForProject(projectId, orgId);
    }
}

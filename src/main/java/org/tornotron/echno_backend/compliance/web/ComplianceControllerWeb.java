package org.tornotron.echno_backend.compliance.web;

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
public class ComplianceControllerWeb {

    private final ComplianceGenerationService complianceGenerationService;

    @PostMapping("/regenerate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public List<InspectionDto> regenerate(@RequestParam Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            throw new TenantIdMissingException("No organization context set; the X-Organization-Id header is required");
        }
        return complianceGenerationService.generateForProject(projectId, orgId);
    }
}

package org.tornotron.echno_backend.compliance.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.compliance.ComplianceGenerationService;
import org.tornotron.echno_backend.compliance.job.ComplianceGenerationJobDto;
import org.tornotron.echno_backend.compliance.job.ComplianceGenerationJobService;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;

import java.util.List;
import java.util.UUID;

/**
 * Manual entry point for AI compliance generation. The automatic path runs on project
 * approval through {@code ComplianceGenerationListener}; this lets a project manager
 * re-run generation on demand (for example after the AI key is configured, or after
 * new rules are added). Generation is idempotent, so a re-run only adds compliances
 * that do not already exist.
 *
 * <h2>Two shapes, for as long as it takes to move</h2>
 *
 * <p>{@code POST /jobs} is the shape that works: it accepts the request, answers in the
 * time an insert takes, and hands back a job to poll. It is the one to build against.
 *
 * <p>{@code POST /regenerate} is the original synchronous call. It holds the request open
 * for the whole run, so it dies at the sixty-second edge timeout once a jurisdiction has
 * about forty rules, and no client budget can change that. Batching the model calls has
 * pushed that boundary out, and it has not moved it far enough to keep. It stays only so
 * that the web client keeps working while it moves to the job endpoints, and it should be
 * deleted once it has.
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
    private final ComplianceGenerationJobService complianceGenerationJobService;

    @PostMapping("/jobs")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Start compliance generation for a project",
            description = "Accepts the work and returns a job to poll with GET /compliance/jobs/{jobId}. "
                    + "Everything decidable without the model is checked before accepting, so a project "
                    + "that cannot be generated for still fails immediately with the reason. A request "
                    + "made while a run for the same project is already in flight joins that run rather "
                    + "than starting a second one, and answers 200 with it instead of 202."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted; the body is the queued job"),
            @ApiResponse(responseCode = "200", description = "A run for this project was already in flight; the body is that job"),
            @ApiResponse(responseCode = "400", description = "No organization context set (the X-Organization-Id header is required), or a precondition is unmet: the project has no type, its address carries no recognisable state, no rules are registered for its jurisdiction, or the AI service is not configured. The detail message states what to fix."),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in the current tenant")
    })
    public ResponseEntity<ComplianceGenerationJobDto> startGeneration(@RequestParam Long projectId) {
        ComplianceGenerationJobService.Accepted accepted =
                complianceGenerationJobService.submit(projectId, requireOrgId());
        return ResponseEntity
                .status(accepted.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(accepted.job());
    }

    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Read one compliance generation job",
            description = "Status, progress and outcome of a single run. Poll this while the status is "
                    + "queued or running. The terminal states are distinct on purpose: succeeded created "
                    + "compliances, nothing-to-report assessed every rule and found nothing to create, "
                    + "and failed did not cover every rule and created nothing."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The job"),
            @ApiResponse(responseCode = "400", description = "No organization context set (the X-Organization-Id header is required)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No job with the given id in the current tenant")
    })
    public ComplianceGenerationJobDto readJob(@PathVariable UUID jobId) {
        return complianceGenerationJobService.find(jobId, requireOrgId());
    }

    @GetMapping("/jobs")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Read the most recent compliance generation job for a project",
            description = "The latest run for a project, whatever state it is in, or 404 if it has never "
                    + "had one. This is how a page that was reloaded during a run finds the run again, "
                    + "since the job id it was holding went with the tab."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The most recent job for the project"),
            @ApiResponse(responseCode = "400", description = "No organization context set (the X-Organization-Id header is required)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "This project has never had a generation job")
    })
    public ComplianceGenerationJobDto readLatestJob(@RequestParam Long projectId) {
        Long orgId = requireOrgId();
        return complianceGenerationJobService.findLatestForProject(projectId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No compliance generation has ever been run for project " + projectId + "."));
    }

    @PostMapping("/regenerate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Regenerate compliance inspections for a project (superseded by POST /compliance/jobs)",
            description = "Runs compliance generation for the given project synchronously and returns the "
                    + "compliance inspections it created. Idempotent: inspections that already exist for a "
                    + "matched rule are not duplicated. Superseded: this call holds the request open for the "
                    + "whole run, so it stops working once a jurisdiction has enough rules for the run to "
                    + "outlast the sixty-second edge timeout. Use POST /compliance/jobs, which accepts the "
                    + "work and returns a job to poll.",
            deprecated = true
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Generation ran; the created inspections are returned (possibly empty when nothing new applied)"),
            @ApiResponse(responseCode = "400", description = "No organization context set (the X-Organization-Id header is required), or a precondition is unmet: the project has no type, its address carries no recognisable state, no rules are registered for its jurisdiction, or the AI service is not configured. The detail message states what to fix."),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in the current tenant"),
            @ApiResponse(responseCode = "409", description = "Another generation run for the same project kept winning the race to create the same compliances; nothing was created by this call and the request can be sent again"),
            @ApiResponse(responseCode = "502", description = "The compliance AI endpoint could not be reached, returned an error, or returned an answer that was cut short by its token limit and so did not cover every candidate rule. Nothing was created; the detail message says which")
    })
    public List<InspectionDto> regenerate(@RequestParam Long projectId) {
        return complianceGenerationService.generateForProject(projectId, requireOrgId());
    }

    private static Long requireOrgId() {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            throw new TenantIdMissingException("No organization context set; the X-Organization-Id header is required");
        }
        return orgId;
    }
}

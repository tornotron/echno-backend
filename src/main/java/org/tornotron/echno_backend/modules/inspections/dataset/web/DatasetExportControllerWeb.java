package org.tornotron.echno_backend.modules.inspections.dataset.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.dataset.DatasetExportRunDto;
import org.tornotron.echno_backend.modules.inspections.dataset.DatasetExportService;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.List;
import java.util.UUID;

/**
 * On-demand runs of the consented evidence export, and their history, for the current
 * organization's system-admin (#791). The run is synchronous and bounded by the per-run cap, so
 * the response is the finished run; a backlog larger than the cap drains over repeated calls.
 */
@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@RequestMapping("/api/v1/inspections/web/dataset-export/runs")
@RequiredArgsConstructor
@Tag(
        name = "Dataset Export",
        description = "Copies the organization's consented inspection images into the dataset bucket "
                + "with a per-run manifest. Refused unless the organization has recorded dataset consent."
)
public class DatasetExportControllerWeb {

    private final DatasetExportService exportService;
    private final UserContextService userContextService;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(summary = "Run the evidence export for the current organization now",
            description = "Copies every not-yet-exported inspection image (inspection evidence, defect "
                    + "photos, observation evidence) to export/<runKey>/ in the dataset bucket and writes "
                    + "manifest.jsonl beside them. Idempotent: a second run exports nothing new.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The finished run"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role"),
            @ApiResponse(responseCode = "409", description = "The organization has not recorded dataset consent")
    })
    public ResponseEntity<DatasetExportRunDto> run() {
        Long orgId = currentOrgId();
        Long userId = userContextService.getCurrentUserId();
        String actor = userId == null ? "user" : "user:" + userId;
        return ResponseEntity.ok(exportService.runForOrganization(orgId, actor));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(summary = "List the organization's export runs, newest first")
    public ResponseEntity<List<DatasetExportRunDto>> list() {
        return ResponseEntity.ok(exportService.listRuns(currentOrgId()));
    }

    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(summary = "Read one export run")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The run"),
            @ApiResponse(responseCode = "404", description = "No such run in this organization")
    })
    public ResponseEntity<DatasetExportRunDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(exportService.getRun(currentOrgId(), id));
    }

    private static Long currentOrgId() {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            throw new TenantIdMissingException("Dataset export needs an organization context");
        }
        return orgId;
    }
}

package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.dtos.ReinspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ReinspectionOutcomeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ScheduleReinspectionRequest;
import org.tornotron.echno_backend.modules.inspections.service.ReinspectionService;

import java.util.List;
import java.util.UUID;

@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(
        name = "Reinspections",
        description = "The re-check of a non-conformance: which inspection re-ran the failed check "
                + "points, by whom, and what it found. Scheduling one creates a new inspection "
                + "carrying the original's failed check points; recording its outcome moves the NCR "
                + "or defect it answers. Attempts are numbered, and one re-check inspection serves "
                + "one attempt. All endpoints are tenant scoped."
)
public class ReinspectionControllerWeb {

    private final ReinspectionService service;

    @PostMapping("/api/v1/ncrs/web/{ncrId}/reinspections")
    @PreAuthorize("@inspectionSecurity.canSignOffNcr(#ncrId)")
    @Operation(
            summary = "Schedule a reinspection of an NCR",
            description = "Creates the reinspection record and a new inspection with the original's "
                    + "failed check points reset to pending (every check point with copyAllItems). "
                    + "Allowed only while the NCR is corrective-action-complete and no earlier "
                    + "attempt is still pending."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reinspection scheduled"),
            @ApiResponse(responseCode = "400", description = "The NCR is not awaiting verification, or an attempt is still pending"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant, or no such inspector")
    })
    public ResponseEntity<ReinspectionDto> scheduleForNcr(@PathVariable UUID ncrId,
                                                          @Valid @RequestBody(required = false) ScheduleReinspectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.scheduleForNcr(ncrId, req == null ? empty() : req));
    }

    @GetMapping("/api/v1/ncrs/web/{ncrId}/reinspections")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "List the reinspection attempts on an NCR", description = "In attempt order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The attempts, oldest first"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant")
    })
    public List<ReinspectionDto> listForNcr(@PathVariable UUID ncrId) {
        return service.findByNcr(ncrId);
    }

    @PostMapping("/api/v1/inspections/web/defects/{defectId}/reinspections")
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(
            summary = "Schedule a reinspection of a defect",
            description = "The defect-only path, for a defect reported resolved with no NCR over it. "
                    + "Same shape as the NCR path; a passed outcome verifies the defect."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reinspection scheduled"),
            @ApiResponse(responseCode = "400", description = "The defect is not resolved, or an attempt is still pending"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No defect with the given id in the current tenant, or no such inspector")
    })
    public ResponseEntity<ReinspectionDto> scheduleForDefect(@PathVariable UUID defectId,
                                                             @Valid @RequestBody(required = false) ScheduleReinspectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.scheduleForDefect(defectId, req == null ? empty() : req));
    }

    @GetMapping("/api/v1/inspections/web/reinspections/{id}")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "Get a reinspection by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reinspection found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No reinspection with the given id in the current tenant")
    })
    public ReinspectionDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping("/api/v1/inspections/web/reinspections/{id}/outcome")
    @PreAuthorize("@inspectionSecurity.canRecordReinspectionOutcome(#id)")
    @Operation(
            summary = "Record what the reinspection found",
            description = "passed or failed, once. A failed re-check of an NCR sends it to rejected; "
                    + "a passed one lets the verify call name this reinspection. On a defect-only "
                    + "reinspection, passed verifies the defect and failed puts it back in progress."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Outcome recorded"),
            @ApiResponse(responseCode = "400", description = "The outcome is pending, or was already recorded"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the sign-off role for this reinspection's discipline"),
            @ApiResponse(responseCode = "404", description = "No reinspection with the given id in the current tenant")
    })
    public ReinspectionDto recordOutcome(@PathVariable UUID id,
                                         @Valid @RequestBody ReinspectionOutcomeRequest req) {
        return service.recordOutcome(id, req);
    }

    private static ScheduleReinspectionRequest empty() {
        return new ScheduleReinspectionRequest(null, null, null);
    }
}

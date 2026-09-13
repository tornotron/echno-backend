package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewStatus;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ReviewObservationRequest;
import org.tornotron.echno_backend.modules.inspections.service.ObservationEvidenceService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@RequestMapping("/api/v1/inspections/web/observations")
@RequiredArgsConstructor
@Tag(
        name = "Observations",
        description = "Findings on site, from a person, a model or a device, each with a persistent id "
                + "from the moment it is recorded. A human observation is accepted on creation; a "
                + "machine one waits in the pending queue for a reviewer to accept, modify or reject it "
                + "and link what it became: a check-item result, a defect, or an inspection. Tenant scoped."
)
public class ObservationControllerWeb {

    private final ObservationService service;
    private final ObservationEvidenceService evidenceService;

    @PostMapping
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(summary = "Record an observation",
            description = "Records a finding by the signed-in inspector. Created accepted, with the caller "
                    + "as reporter and reviewer; a supervisor may still reject it later.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Observation recorded"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection or spatial node with the given id in the current tenant")
    })
    public ResponseEntity<ObservationDto> create(@Valid @RequestBody CreateObservationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "Read an observation")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Observation returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No observation with the given id in the current tenant")
    })
    public ObservationDto read(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "List observations",
            description = "Paged, filtered by project, review status, source, inspection, site structure "
                    + "subtree and observed-at window. The pending queue is reviewStatus=pending.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of observations returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<ObservationDto> list(@RequestParam(required = false) Long projectId,
                                     @RequestParam(required = false) ObservationReviewStatus reviewStatus,
                                     @RequestParam(required = false) ObservationSource source,
                                     @RequestParam(required = false) UUID inspectionId,
                                     @RequestParam(required = false) UUID spatialNodeId,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                     Pageable pageable) {
        return service.findAll(projectId, reviewStatus, source, inspectionId, spatialNodeId, from, to, pageable);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("@inspectionSecurity.canReviewObservations()")
    @Operation(summary = "Decide on a pending observation",
            description = "Accept, modify or reject. Accept and modify link the outcome: mark a check item, "
                    + "create or attach a defect, or confirm an inspection. Modify stores the reviewer's "
                    + "edits as a diff and leaves the proposal as the producer wrote it. Reject needs a note. "
                    + "One decision per observation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decision recorded"),
            @ApiResponse(responseCode = "400", description = "Reject without a note, modify without changes, or an incomplete outcome"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No observation, check item, defect or inspection with the given id"),
            @ApiResponse(responseCode = "409", description = "The observation has already been reviewed")
    })
    public ObservationDto review(@PathVariable UUID id, @Valid @RequestBody ReviewObservationRequest req) {
        return service.review(id, req);
    }

    @GetMapping("/{id}/evidence")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "List an observation's evidence")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evidence returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No observation with the given id in the current tenant")
    })
    public List<AttachmentDto> readEvidence(@PathVariable UUID id) {
        return evidenceService.list(id);
    }

    @PostMapping("/{id}/evidence/presign")
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(summary = "Presign evidence uploads for an observation",
            description = "Step one of the direct-to-storage path, as for inspection evidence.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned upload URLs returned"),
            @ApiResponse(responseCode = "400", description = "A file was declared twice, or is already attached"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No observation with the given id in the current tenant")
    })
    public List<PresignedUpload> presignEvidence(@PathVariable UUID id, @RequestBody List<UploadRequest> uploads) {
        return evidenceService.presign(id, uploads);
    }

    @PostMapping("/{id}/evidence/register")
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(summary = "Register presigned evidence uploads for an observation",
            description = "Step two of the direct-to-storage path: confirms the keys once each object is "
                    + "verified present in storage.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Evidence registered"),
            @ApiResponse(responseCode = "400", description = "A referenced object is missing from storage, or its key was not presigned for this observation"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No observation with the given id in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> registerEvidence(@PathVariable UUID id,
                                                                @RequestBody List<RegisterUploadRequest> uploads) {
        return ResponseEntity.status(HttpStatus.CREATED).body(evidenceService.register(id, uploads));
    }
}

package org.tornotron.echno_backend.modules.workprogress.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.modules.workprogress.WorkProgressModule;
import org.tornotron.echno_backend.modules.workprogress.dto.ProgressInspectionDto;
import org.tornotron.echno_backend.modules.workprogress.dto.RecordProgressInspectionRequest;
import org.tornotron.echno_backend.modules.workprogress.service.ProgressInspectionService;

/**
 * The web surface of progress inspections, the twin of {@link ProgressInspectionController} under {@code /web},
 * where the project manager reviews and records from the office. The two expose the same operations under the same guards.
 */
@RestController
@RequestMapping("/api/v1/progress-inspections/web")
@RequireSubscription(feature = WorkProgressModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Progress Inspections (web)", description = "Web twin of the progress inspection surface: reads for members, recording for the project team.")
public class ProgressInspectionControllerWeb {

    private final ProgressInspectionService service;

    @GetMapping
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "List progress inspections, newest first",
            description = "Filter by project and by activity; both are optional.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of progress inspections"))
    public Page<ProgressInspectionDto> list(@RequestParam(required = false) Long projectId,
                                            @RequestParam(required = false) Long wbsElementId,
                                            @Valid PageQuery page) {
        return service.list(projectId, wbsElementId, page.getPageNo(), page.getPageSize());
    }

    @GetMapping("/{id}")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "Get one progress inspection")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The progress inspection"),
            @ApiResponse(responseCode = "404", description = "No such progress inspection in the current tenant")
    })
    public ProgressInspectionDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize(WorkProgressModule.RECORD_GUARD)
    @Operation(summary = "Record a progress inspection of an activity",
            description = "Records whether the activity was done, partly done or not done on the inspection date, with the "
                    + "actual dates, a forecast finish and the reason for any delay, and applies it to the activity. The "
                    + "planned dates and the rest of the schedule are not changed.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Progress inspection recorded and applied to the activity"),
            @ApiResponse(responseCode = "400", description = "A value breaks a rule: the outcome and percent disagree, a date is missing or out of order, a delay has no reason, or the activity is not a leaf or is already completed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such activity in the current tenant")
    })
    public ResponseEntity<ProgressInspectionDto> record(@Valid @RequestBody RecordProgressInspectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.record(req));
    }

    @GetMapping("/{id}/evidence")
    @PreAuthorize(WorkProgressModule.READ_GUARD)
    @Operation(summary = "List a progress inspection's evidence")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evidence returned"),
            @ApiResponse(responseCode = "404", description = "No such progress inspection in the current tenant")
    })
    public List<AttachmentDto> listEvidence(@PathVariable UUID id) {
        return service.listEvidence(id);
    }

    @PostMapping("/{id}/evidence/presign")
    @PreAuthorize(WorkProgressModule.RECORD_GUARD)
    @Operation(summary = "Presign evidence uploads for a progress inspection",
            description = "Step one of the direct-to-storage path, as for inspection evidence.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned upload URLs returned"),
            @ApiResponse(responseCode = "400", description = "A file was declared twice, or is already attached"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such progress inspection in the current tenant")
    })
    public List<PresignedUpload> presignEvidence(@PathVariable UUID id, @RequestBody List<UploadRequest> uploads) {
        return service.presignEvidence(id, uploads);
    }

    @PostMapping("/{id}/evidence/register")
    @PreAuthorize(WorkProgressModule.RECORD_GUARD)
    @Operation(summary = "Register presigned evidence uploads for a progress inspection",
            description = "Step two of the direct-to-storage path: confirms the keys once each object is verified present in storage.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Evidence registered"),
            @ApiResponse(responseCode = "400", description = "A referenced object is missing from storage, or its key was not presigned for this inspection"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such progress inspection in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> registerEvidence(@PathVariable UUID id,
                                                                @RequestBody List<RegisterUploadRequest> uploads) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerEvidence(id, uploads));
    }
}

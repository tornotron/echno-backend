package org.tornotron.echno_backend.inspection.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.inspection.InspectionCategory;
import org.tornotron.echno_backend.inspection.InspectionResult;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.InspectionType;
import org.tornotron.echno_backend.inspection.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.dtos.ReplaceAnnotationsRequest;
import org.tornotron.echno_backend.inspection.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.inspection.pdf.InspectionReportPdfService;
import org.tornotron.echno_backend.inspection.service.DefectAnnotationService;
import org.tornotron.echno_backend.inspection.service.InspectionEvidenceService;
import org.tornotron.echno_backend.inspection.service.InspectionService;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inspections/web")
@RequiredArgsConstructor
@Tag(
        name = "Inspections",
        description = "Site inspections carried out against a project, covering routine, safety and "
                + "compliance types. An inspection tracks its checklist items, defects, result and status. "
                + "All endpoints are tenant scoped. Reading is open to the project manager, QA engineer, "
                + "safety officer and site engineer; scheduling and recording an inspection is not open "
                + "to the site engineer, who acts on the non-conformances an inspection raises rather "
                + "than carrying it out."
)
public class InspectionControllerWeb {

    private final InspectionService service;
    private final DefectAnnotationService annotationService;
    private final InspectionEvidenceService evidenceService;
    private final InspectionReportPdfService reportService;

    @PostMapping
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(
            summary = "Create an inspection",
            description = "Creates a new inspection for a project, including its checklist items."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Inspection created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in the current tenant")
    })
    public ResponseEntity<InspectionDto> create(@Valid @RequestBody CreateInspectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "Get an inspection by id",
            description = "Returns a single inspection including its checklist items and any recorded "
                    + "defects."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Inspection found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public InspectionDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "List inspections",
            description = "Returns a page of inspections in the current tenant. The projectId, status, "
                    + "type, category, trade and result parameters are optional filters; omitting all of "
                    + "them returns every inspection, subject to paging."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching inspections"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<InspectionDto> list(@RequestParam(required = false) Long projectId,
                                    @RequestParam(required = false) InspectionStatus status,
                                    @RequestParam(required = false) InspectionType type,
                                    @RequestParam(required = false) InspectionCategory category,
                                    @RequestParam(required = false) InspectionTrade trade,
                                    @RequestParam(required = false) InspectionResult result,
                                    Pageable pageable) {
        return service.findAll(projectId, status, type, category, trade, result, pageable);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(
            summary = "Update an inspection",
            description = "Updates the status, result, checklist items and defects of an existing "
                    + "inspection identified by id. The project the inspection is against is fixed "
                    + "when it is created and cannot be changed here."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Inspection updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body, or the "
                    + "payload names a different project"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public InspectionDto update(@PathVariable UUID id,
                                @Valid @RequestBody UpdateInspectionRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/{id}/annotations")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "List the marks drawn over an inspection's defect photos",
            description = "Returns a page of annotations. Each one names the photo it is drawn on, "
                    + "exactly as that photo appears in a defect's photos list, and positions itself "
                    + "as fractions of the image so it holds its place at any rendered size."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of annotations"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public Page<DefectPhotoAnnotationDto> listAnnotations(@PathVariable UUID id, Pageable pageable) {
        return annotationService.findByInspection(id, pageable);
    }

    @PutMapping("/{id}/annotations")
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(
            summary = "Replace the marks drawn over an inspection's defect photos",
            description = "Stores the supplied set and discards whatever was there before, so one "
                    + "save records the whole mark-up canvas. Every mark must name a photo that one "
                    + "of the inspection's defects actually carries. Marks do not survive the photo "
                    + "they are drawn on being replaced: the coordinates describe a region of that "
                    + "image, so on a different one they would be evidence pointing at nothing."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Annotations stored"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or a mark names a photo "
                    + "no defect on this inspection carries"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public List<DefectPhotoAnnotationDto> replaceAnnotations(
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceAnnotationsRequest req) {
        return annotationService.replaceForInspection(id, req);
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "Download the QA/QC inspection report",
            description = "Renders the inspection into a PDF: its check points with the acceptance "
                    + "criterion, tolerance, measurement and computed deviation for each, its defects, "
                    + "the summary counts, and the annotated photographs of those defects. The check "
                    + "point and defect tables print at most 500 rows each; the report states the true "
                    + "totals and flags itself when it is showing only part of them."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF generated and returned as an attachment"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant"),
            @ApiResponse(responseCode = "500", description = "PDF rendering failed")
    })
    public ResponseEntity<byte[]> downloadReport(@PathVariable UUID id) throws IOException {
        RenderedReport report = reportService.render(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + report.documentName() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(report.content());
    }

    // Evidence: the paperwork an inspection rests on, filed against the inspection itself.
    //
    // These sit here rather than on the generic attachment controller because that one keys on a
    // numeric entity id and an inspection is keyed by UUID, and because the removal rule below is
    // an inspection rule, not an attachment rule. Uploading is the same two shapes as everywhere
    // else: straight through the API for a scanned certificate, presign then register for a large
    // file that should not pass through the API or the CDN in front of it.
    //
    // What kind of document a file is, when it was issued and when it lapses are recorded
    // afterwards through PATCH /api/v1/attachment/web/attachmentId/{attachmentId}/document, which
    // already serves every module and needed nothing added for this one.

    @GetMapping("/{id}/evidence")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "List an inspection's evidence",
            description = "Returns the files filed against the inspection: the permit, certificate "
                    + "or approval a compliance inspection stands in for, test reports, and any other "
                    + "supporting document, each with a time-limited download URL and, where one was "
                    + "recorded, its document type and expiry. An AI-suggested inspection is a "
                    + "recommendation and its evidence list being empty is the normal state; a "
                    + "suggestion is not itself proof of compliance."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Evidence returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public List<AttachmentDto> readEvidence(@PathVariable UUID id) {
        return evidenceService.list(id);
    }

    @PostMapping(value = "/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(
            summary = "Attach evidence to an inspection",
            description = "Uploads one or more files straight through the API and files them against "
                    + "the inspection. Accepted at any point in the inspection's life, including after "
                    + "it has passed: a certificate routinely arrives after the work was signed off, "
                    + "and that is exactly the record this exists to hold."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Evidence attached"),
            @ApiResponse(responseCode = "400", description = "No files were sent, or one is already attached"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> attachEvidence(
            @PathVariable UUID id,
            @RequestParam("attachments") List<MultipartFile> attachments) {
        return ResponseEntity.status(HttpStatus.CREATED).body(evidenceService.upload(id, attachments));
    }

    @PostMapping("/{id}/evidence/presign")
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(
            summary = "Presign evidence uploads",
            description = "Step one of the direct-to-storage path: for each declared file, returns a "
                    + "short-lived URL the client PUTs it to, bypassing the API and the CDN in front "
                    + "of it. Site photographs and scanned approvals routinely exceed what the "
                    + "streaming path allows."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned upload URLs returned"),
            @ApiResponse(responseCode = "400", description = "A file was declared twice, or is already attached"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public List<PresignedUpload> presignEvidence(
            @PathVariable UUID id,
            @RequestBody List<UploadRequest> uploads) {
        return evidenceService.presign(id, uploads);
    }

    @PostMapping("/{id}/evidence/register")
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(
            summary = "Register presigned evidence uploads",
            description = "Step two of the direct-to-storage path: confirms the storage keys once "
                    + "each object is verified present in storage, so a caller cannot record evidence "
                    + "for a file that was never uploaded."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Evidence registered"),
            @ApiResponse(responseCode = "400", description = "A referenced object is missing from storage"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> registerEvidence(
            @PathVariable UUID id,
            @RequestBody List<RegisterUploadRequest> uploads) {
        return ResponseEntity.status(HttpStatus.CREATED).body(evidenceService.register(id, uploads));
    }

    @DeleteMapping("/{id}/evidence/{attachmentId}")
    @PreAuthorize("@inspectionSecurity.canManageInspections()")
    @Operation(
            summary = "Remove evidence from an inspection",
            description = "Deletes one file and its stored object. Refused once the inspection has "
                    + "reached a verdict, that is once it is passed, passed with remarks or failed: "
                    + "those files were part of the record when somebody reached that conclusion. A "
                    + "file that turns out to be wrong is superseded by uploading the correct one."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Evidence removed"),
            @ApiResponse(responseCode = "400", description = "The inspection has been judged and its evidence is frozen"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such inspection, or the file is not filed against it")
    })
    public ResponseEntity<Void> removeEvidence(@PathVariable UUID id, @PathVariable Long attachmentId) {
        evidenceService.delete(id, attachmentId);
        return ResponseEntity.noContent().build();
    }
}

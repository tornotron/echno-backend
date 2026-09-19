package org.tornotron.echno_backend.modules.toolboxtalks.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import org.tornotron.echno_backend.modules.toolboxtalks.ToolboxTalksModule;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.CreateToolboxTalkRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkAttendeesRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkDto;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.UpdateToolboxTalkRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.pdf.ToolboxTalkPdfService;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;
import org.tornotron.echno_backend.modules.toolboxtalks.service.ToolboxTalksService;

/**
 * The web surface of the Toolbox Talks module: the twin of {@link ToolboxTalksController}
 * under {@code /web}, where the safety officer reviews and the office records on a crew's
 * behalf. The two twins expose the same operations under the same guards.
 */
@RestController
@RequestMapping("/api/v1/toolbox-talks/web")
@RequireSubscription(feature = ToolboxTalksModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Toolbox Talks (web)", description = "Web twin of the Toolbox Talks surface: reads for members, writes for the roles that run site safety.")
public class ToolboxTalksControllerWeb {

    private final ToolboxTalksService service;
    private final ToolboxTalkPdfService pdf;

    @GetMapping
    @PreAuthorize(ToolboxTalksModule.READ_GUARD)
    @Operation(summary = "List toolbox talks, newest first",
            description = "Filter by project, by talk date range and by status; every filter is optional.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of talks"))
    public Page<ToolboxTalkDto> list(@RequestParam(required = false) Long projectId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) ToolboxTalkStatus status,
                                     @Valid PageQuery page) {
        return service.list(projectId, from, to, status, page.getPageNo(), page.getPageSize());
    }

    @GetMapping("/{id}")
    @PreAuthorize(ToolboxTalksModule.READ_GUARD)
    @Operation(summary = "Get one toolbox talk")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The talk"),
            @ApiResponse(responseCode = "404", description = "No such talk in the current tenant")
    })
    public ToolboxTalkDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize(ToolboxTalksModule.MANAGE_GUARD)
    @Operation(summary = "Draft a toolbox talk")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Talk drafted"),
            @ApiResponse(responseCode = "400", description = "The date is too far ahead, or a person is not an active employee"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such project in the current tenant")
    })
    public ResponseEntity<ToolboxTalkDto> create(@Valid @RequestBody CreateToolboxTalkRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize(ToolboxTalksModule.MANAGE_GUARD)
    @Operation(summary = "Change a drafted toolbox talk")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Talk updated"),
            @ApiResponse(responseCode = "400", description = "The talk is already recorded, or a value fails a rule"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such talk in the current tenant")
    })
    public ToolboxTalkDto update(@PathVariable UUID id, @Valid @RequestBody UpdateToolboxTalkRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/attendees")
    @PreAuthorize(ToolboxTalksModule.MANAGE_GUARD)
    @Operation(summary = "Add attendees to a drafted toolbox talk")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attendance updated"),
            @ApiResponse(responseCode = "400", description = "The talk is already recorded, or a person is not an active employee"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such talk in the current tenant")
    })
    public ToolboxTalkDto addAttendees(@PathVariable UUID id, @Valid @RequestBody ToolboxTalkAttendeesRequest req) {
        return service.addAttendees(id, req.employeeIds());
    }

    @DeleteMapping("/{id}/attendees/{employeeId}")
    @PreAuthorize(ToolboxTalksModule.MANAGE_GUARD)
    @Operation(summary = "Remove an attendee from a drafted toolbox talk")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attendance updated"),
            @ApiResponse(responseCode = "400", description = "The talk is already recorded"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such talk, or the employee is not on its attendance")
    })
    public ToolboxTalkDto removeAttendee(@PathVariable UUID id, @PathVariable Long employeeId) {
        return service.removeAttendee(id, employeeId);
    }

    @PostMapping("/{id}/record")
    @PreAuthorize(ToolboxTalksModule.MANAGE_GUARD)
    @Operation(summary = "Record a toolbox talk",
            description = "Signs the draft off as the safety record. It needs at least one attendee and happens once.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Talk recorded"),
            @ApiResponse(responseCode = "400", description = "The talk is already recorded, or has no attendees"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such talk in the current tenant")
    })
    public ToolboxTalkDto record(@PathVariable UUID id) {
        return service.record(id);
    }

    @GetMapping("/{id}/photos")
    @PreAuthorize(ToolboxTalksModule.READ_GUARD)
    @Operation(summary = "List a toolbox talk's photo evidence")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Photos returned"),
            @ApiResponse(responseCode = "404", description = "No such talk in the current tenant")
    })
    public List<AttachmentDto> listPhotos(@PathVariable UUID id) {
        return service.listPhotos(id);
    }

    @PostMapping("/{id}/photos/presign")
    @PreAuthorize(ToolboxTalksModule.MANAGE_GUARD)
    @Operation(summary = "Presign photo uploads for a toolbox talk",
            description = "Step one of the direct-to-storage path, as for inspection evidence.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned upload URLs returned"),
            @ApiResponse(responseCode = "400", description = "A file was declared twice, or is already attached"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such talk in the current tenant")
    })
    public List<PresignedUpload> presignPhotos(@PathVariable UUID id, @RequestBody List<UploadRequest> uploads) {
        return service.presignPhotos(id, uploads);
    }

    @PostMapping("/{id}/photos/register")
    @PreAuthorize(ToolboxTalksModule.MANAGE_GUARD)
    @Operation(summary = "Register presigned photo uploads for a toolbox talk",
            description = "Step two of the direct-to-storage path: confirms the keys once each object is verified present in storage.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Photos registered"),
            @ApiResponse(responseCode = "400", description = "A referenced object is missing from storage, or its key was not presigned for this talk"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such talk in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> registerPhotos(@PathVariable UUID id,
                                                              @RequestBody List<RegisterUploadRequest> uploads) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerPhotos(id, uploads));
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(ToolboxTalksModule.READ_GUARD)
    @Operation(summary = "Export a toolbox talk as a one-page PDF record")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The PDF"),
            @ApiResponse(responseCode = "404", description = "No such talk in the current tenant")
    })
    public ResponseEntity<byte[]> exportPdf(@PathVariable UUID id) throws IOException {
        RenderedReport report = pdf.render(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + report.documentName() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(report.content());
    }
}

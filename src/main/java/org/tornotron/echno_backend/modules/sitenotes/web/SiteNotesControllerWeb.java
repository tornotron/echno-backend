package org.tornotron.echno_backend.modules.sitenotes.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import org.tornotron.echno_backend.modules.sitenotes.SiteNotesModule;
import org.tornotron.echno_backend.modules.sitenotes.dto.CreateSiteNoteRequest;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNoteDto;
import org.tornotron.echno_backend.modules.sitenotes.dto.UpdateSiteNoteRequest;
import org.tornotron.echno_backend.modules.sitenotes.service.SiteNotesService;

/**
 * The web surface of the Site Notes module: the twin of {@link SiteNotesController} under
 * {@code /web}, where the office-side screens read and write. The two twins expose the same
 * operations under the same guards.
 */
@RestController
@RequestMapping("/api/v1/site-notes/web")
@RequireSubscription(feature = SiteNotesModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Site Notes (web)", description = "Site notes of the current tenant: reads for members, writes for the roles that run a site.")
public class SiteNotesControllerWeb {

    private final SiteNotesService service;

    @GetMapping
    @PreAuthorize(SiteNotesModule.READ_GUARD)
    @Operation(summary = "List site notes, newest first",
            description = "Filter by project and by note date range; every filter is optional.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of notes"))
    public Page<SiteNoteDto> list(@RequestParam(required = false) Long projectId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @Valid PageQuery page) {
        return service.list(projectId, from, to, page.getPageNo(), page.getPageSize());
    }

    @GetMapping("/{id}")
    @PreAuthorize(SiteNotesModule.READ_GUARD)
    @Operation(summary = "Get one site note")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The note"),
            @ApiResponse(responseCode = "404", description = "No such note in the current tenant")
    })
    public SiteNoteDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize(SiteNotesModule.MANAGE_GUARD)
    @Operation(summary = "Add a site note")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Note added"),
            @ApiResponse(responseCode = "400", description = "The date is too far ahead, or the author is not an active employee"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such project in the current tenant")
    })
    public ResponseEntity<SiteNoteDto> create(@Valid @RequestBody CreateSiteNoteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize(SiteNotesModule.MANAGE_GUARD)
    @Operation(summary = "Change a site note's date and text")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Note updated"),
            @ApiResponse(responseCode = "400", description = "The date is too far ahead"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such note in the current tenant")
    })
    public SiteNoteDto update(@PathVariable UUID id, @Valid @RequestBody UpdateSiteNoteRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/{id}/photos")
    @PreAuthorize(SiteNotesModule.READ_GUARD)
    @Operation(summary = "List a site note's photos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Photos returned"),
            @ApiResponse(responseCode = "404", description = "No such note in the current tenant")
    })
    public List<AttachmentDto> listPhotos(@PathVariable UUID id) {
        return service.listPhotos(id);
    }

    @PostMapping("/{id}/photos/presign")
    @PreAuthorize(SiteNotesModule.MANAGE_GUARD)
    @Operation(summary = "Presign photo uploads for a site note",
            description = "Step one of the direct-to-storage path.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned upload URLs returned"),
            @ApiResponse(responseCode = "400", description = "A file was declared twice, or is already attached"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such note in the current tenant")
    })
    public List<PresignedUpload> presignPhotos(@PathVariable UUID id, @RequestBody List<UploadRequest> uploads) {
        return service.presignPhotos(id, uploads);
    }

    @PostMapping("/{id}/photos/register")
    @PreAuthorize(SiteNotesModule.MANAGE_GUARD)
    @Operation(summary = "Register presigned photo uploads for a site note",
            description = "Step two of the direct-to-storage path: confirms the keys once each object is verified present in storage.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Photos registered"),
            @ApiResponse(responseCode = "400", description = "A referenced object is missing from storage, or its key was not presigned for this note"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No such note in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> registerPhotos(@PathVariable UUID id,
                                                              @RequestBody List<RegisterUploadRequest> uploads) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerPhotos(id, uploads));
    }
}

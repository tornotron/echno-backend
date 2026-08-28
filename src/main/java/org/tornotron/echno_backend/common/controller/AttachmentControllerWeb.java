package org.tornotron.echno_backend.common.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import org.tornotron.echno_backend.common.dto.AttachmentDocumentMetadataDto;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;

import java.time.Duration;
import java.util.List;

// NOTE (phase0 authz lockdown): the @PreAuthorize guards below require the caller
// to be a member of the current tenant, which closes the "any authenticated user"
// hole. They do NOT yet verify that the target entity/attachment belongs to the
// caller's organization; a follow-up in AttachmentService must resolve the
// attachment/entity to its org and reject cross-tenant ids (org-scoped finder),
// so a member of org A cannot act on org B's attachment by numeric id.
@RestController
@RequestMapping("/api/v1/attachment/web")
@Validated
@Tag(
        name = "Attachments",
        description = "Files attached to a task, project, issue or other entity. Covers the direct "
                + "multipart upload path and the two-step presign/register path for large files that go "
                + "straight to object storage, plus reading and deleting attachments by entity. Access "
                + "is gated by tenant membership."
)
public class AttachmentControllerWeb {

    private final AttachmentService attachmentService;
    private final FileStorageService fileStorageService;

    public AttachmentControllerWeb(AttachmentService attachmentService, FileStorageService fileStorageService) {
        this.attachmentService = attachmentService;
        this.fileStorageService = fileStorageService;
    }

    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,value = "/entityId/{entityId}/entityType/{entityType}")
    @Operation(
            summary = "Upload attachments directly",
            description = "Uploads one or more files straight through the API and attaches them to the "
                    + "given entity. Suitable for small files; large files should use the presign/register "
                    + "path instead."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Attachments created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "No attachments part was sent"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> creatAttachment(@RequestParam(value = "attachments",required = true)List<MultipartFile> attachments,
                                                         @PathVariable String entityType,
                                                         @PathVariable Long entityId)  {
        return ResponseEntity.status(HttpStatus.CREATED).body(attachmentService.uploadAttachments(attachments,entityType,entityId,entityType.split("_",2)[0].toLowerCase())
                .stream()
                .map(attachment -> {
                    AttachmentDto dto = new AttachmentDto();
                    dto.setId(attachment.getId());
                    dto.setEntityType(attachment.getEntityType());
                    dto.setContentType(attachment.getContentType());
                    dto.setFileSize(attachment.getFileSize());
                    dto.setFileName(attachment.getOriginalFilename());
                    dto.setUrl(fileStorageService.generateDownloadUrl(attachment.getStorageKey(), Duration.ofHours(1)));
                    dto.setCreatedAt(attachment.getCreatedAt().toString());
                    return dto;
                }).toList());
    }

    // Direct-to-storage upload, in two steps, so large files never pass through
    // the API or the CDN in front of it.
    //
    // 1. presign: the client declares the files, and gets a short-lived upload
    //    URL for each. The client PUTs each file straight to storage.
    // 2. register: the client confirms the keys, and the server records them
    //    after verifying each object is actually present in storage.
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @PostMapping("/presign/entityId/{entityId}/entityType/{entityType}")
    @Operation(
            summary = "Presign attachment uploads",
            description = "Step 1 of the direct-to-storage upload path: for each declared file, returns "
                    + "a short-lived URL the client can PUT the file to directly, bypassing the API and "
                    + "the CDN in front of it."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Presigned upload URLs returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<PresignedUpload>> presignUploads(
            @RequestBody List<UploadRequest> uploads,
            @PathVariable String entityType,
            @PathVariable Long entityId) {
        return ResponseEntity.ok(attachmentService.presignUploads(
                uploads, entityType, entityId, entityType.split("_", 2)[0].toLowerCase()));
    }

    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @PostMapping("/register/entityId/{entityId}/entityType/{entityType}")
    @Operation(
            summary = "Register presigned uploads",
            description = "Step 2 of the direct-to-storage upload path: after the client has PUT each "
                    + "file to its presigned URL, confirms the storage keys and records the attachments "
                    + "once each object is verified present in storage."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Attachments registered"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A referenced object is missing from storage, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> registerUploads(
            @RequestBody List<RegisterUploadRequest> uploads,
            @PathVariable String entityType,
            @PathVariable Long entityId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                attachmentService.registerUploads(
                        uploads, entityType, entityId, entityType.split("_", 2)[0].toLowerCase())
                        .stream()
                        .map(attachment -> {
                            AttachmentDto dto = new AttachmentDto();
                            dto.setId(attachment.getId());
                            dto.setEntityType(attachment.getEntityType());
                            dto.setContentType(attachment.getContentType());
                            dto.setFileSize(attachment.getFileSize());
                            dto.setFileName(attachment.getOriginalFilename());
                            dto.setUrl(fileStorageService.generateDownloadUrl(attachment.getStorageKey(), Duration.ofHours(1)));
                            dto.setCreatedAt(attachment.getCreatedAt().toString());
                            return dto;
                        }).toList());
    }

    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @GetMapping("/entityId/{entityId}/entityType/{entityType}")
    @Operation(
            summary = "List attachments for an entity",
            description = "Returns every attachment recorded against the given entity id and type, "
                    + "each with a time-limited download URL."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Attachments returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<AttachmentDto>> readAttachments(@PathVariable String entityType,
                                                               @PathVariable Long entityId) {
        return ResponseEntity.status(HttpStatus.OK).body(attachmentService.getAttachments(entityType,entityId));
    }

    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @PatchMapping("/attachmentId/{attachmentId}/document")
    @Operation(
            summary = "Record what an attachment is and when it expires",
            description = "Sets the document type, issue date and expiry date on an already-uploaded "
                    + "file. Applied after the upload so it works for both upload paths and for any "
                    + "entity that files documents, for example an asset's insurance policy or "
                    + "warranty. Any field sent as null is cleared."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Document metadata recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No attachment with the given id in the caller's organization")
    })
    public ResponseEntity<AttachmentDto> updateDocumentMetadata(
            @PathVariable Long attachmentId,
            @Valid @RequestBody AttachmentDocumentMetadataDto metadata) {
        return ResponseEntity.ok(attachmentService.updateDocumentMetadata(attachmentId, metadata));
    }

    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @DeleteMapping("/attachmentId/{attachmentId}")
    @Operation(
            summary = "Delete an attachment",
            description = "Deletes the attachment with the given id, including its stored file."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Attachment deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No attachment with the given id")
    })
    public ResponseEntity<ApiResponse> deleteAttachment(@PathVariable Long attachmentId) {
        attachmentService.deleteAttachment(attachmentId);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Deleted Attachment"));
    }
}

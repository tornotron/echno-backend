package org.tornotron.echno_backend.common.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/attachment/web")
@Validated
public class AttachmentControllerWeb {

    private final AttachmentService attachmentService;
    private final FileStorageService fileStorageService;

    public AttachmentControllerWeb(AttachmentService attachmentService, FileStorageService fileStorageService) {
        this.attachmentService = attachmentService;
        this.fileStorageService = fileStorageService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,value = "/entityId/{entityId}/entityType/{entityType}")
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
    @PostMapping("/presign/entityId/{entityId}/entityType/{entityType}")
    public ResponseEntity<List<PresignedUpload>> presignUploads(
            @RequestBody List<UploadRequest> uploads,
            @PathVariable String entityType,
            @PathVariable Long entityId) {
        return ResponseEntity.ok(attachmentService.presignUploads(
                uploads, entityType, entityId, entityType.split("_", 2)[0].toLowerCase()));
    }

    @PostMapping("/register/entityId/{entityId}/entityType/{entityType}")
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

    @GetMapping("/entityId/{entityId}/entityType/{entityType}")
    public ResponseEntity<List<AttachmentDto>> readAttachments(@PathVariable String entityType,
                                                               @PathVariable Long entityId) {
        return ResponseEntity.status(HttpStatus.OK).body(attachmentService.getAttachments(entityType,entityId));
    }

    @DeleteMapping("/attachmentId/{attachmentId}")
    public ResponseEntity<ApiResponse> deleteAttachment(@PathVariable Long attachmentId) {
        attachmentService.deleteAttachment(attachmentId);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Deleted Attachment"));
    }
}

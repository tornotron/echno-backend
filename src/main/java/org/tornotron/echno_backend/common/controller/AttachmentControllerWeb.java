package org.tornotron.echno_backend.common.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
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
        return ResponseEntity.status(HttpStatus.CREATED).body(attachmentService.uploadAttachments(attachments,entityType,entityId,entityType.toLowerCase())
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

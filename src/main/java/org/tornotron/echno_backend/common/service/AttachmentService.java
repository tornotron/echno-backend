package org.tornotron.echno_backend.common.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.dto.StoredFile;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing attachments across different modules.
 * Combines file storage operations with attachment record management.
 */
@Service
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final FileStorageService fileStorageService;

    public AttachmentService(AttachmentRepository attachmentRepository, FileStorageService fileStorageService) {
        this.attachmentRepository = attachmentRepository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Uploads files and creates attachment records for an entity.
     *
     * @param files      The files to upload
     * @param entityType The type of entity (e.g., "PROJECT", "TASK")
     * @param entityId   The ID of the entity
     * @param folder     The storage folder for the files
     * @return List of created Attachment entities
     */
    @Transactional
    public List<Attachment> uploadAttachments(List<MultipartFile> files, String entityType, Long entityId, String folder) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }

        List<Attachment> attachments = new ArrayList<>();
        List<StoredFile> storedFiles = fileStorageService.uploadFiles(files, folder);

        for (int i = 0; i < storedFiles.size(); i++) {
            StoredFile storedFile = storedFiles.get(i);
            MultipartFile originalFile = files.get(i);

            Attachment attachment = new Attachment();
            attachment.setEntityType(entityType);
            attachment.setEntityId(entityId);
            attachment.setStorageKey(storedFile.key());
            attachment.setContentType(storedFile.contentType());
            attachment.setFileSize(storedFile.size());
            attachment.setOriginalFilename(originalFile.getOriginalFilename());

            attachments.add(attachmentRepository.save(attachment));
        }

        return attachments;
    }

    /**
     * Uploads a single file and creates an attachment record.
     *
     * @param file       The file to upload
     * @param entityType The type of entity
     * @param entityId   The ID of the entity
     * @param folder     The storage folder
     * @return The created Attachment entity
     */
    @Transactional
    public Attachment uploadAttachment(MultipartFile file, String entityType, Long entityId, String folder) {
        StoredFile storedFile = fileStorageService.uploadFile(file, folder);

        Attachment attachment = new Attachment();
        attachment.setEntityType(entityType);
        attachment.setEntityId(entityId);
        attachment.setStorageKey(storedFile.key());
        attachment.setContentType(storedFile.contentType());
        attachment.setFileSize(storedFile.size());
        attachment.setOriginalFilename(file.getOriginalFilename());

        return attachmentRepository.save(attachment);
    }

    /**
     * Retrieves all attachments for an entity.
     *
     * @param entityType The type of entity
     * @param entityId   The ID of the entity
     * @return List of attachments
     */
    @Transactional(readOnly = true)
    public List<AttachmentDto> getAttachments(String entityType, Long entityId) {
        return attachmentRepository.findByEntityTypeAndEntityId(entityType, entityId)
                .stream()
                .map(attachment -> {
                    AttachmentDto dto = new AttachmentDto();
                    dto.setEntityType(attachment.getEntityType());
                    dto.setContentType(attachment.getContentType());
                    dto.setFileSize(attachment.getFileSize());
                    dto.setFileName(attachment.getOriginalFilename());
                    dto.setUrl(fileStorageService.generateDownloadUrl(attachment.getStorageKey(), Duration.ofHours(1)));
                    dto.setCreatedAt(attachment.getCreatedAt().toString());
                    return dto;
                }).toList();
    }

    /**
     * Deletes a single attachment by ID, including the file from storage.
     *
     * @param attachmentId The ID of the attachment to delete
     */
    @Transactional
    public void deleteAttachment(Long attachmentId) {
        attachmentRepository.findById(attachmentId).ifPresent(attachment -> {
            fileStorageService.deleteFile(attachment.getStorageKey());
            attachmentRepository.delete(attachment);
        });
    }

    /**
     * Deletes all attachments for an entity, including files from storage.
     *
     * @param entityType The type of entity
     * @param entityId   The ID of the entity
     */
    @Transactional
    public void deleteAllAttachments(String entityType, Long entityId) {
        List<Attachment> attachments = attachmentRepository.findByEntityTypeAndEntityId(entityType, entityId);

        // Delete files from storage
        List<String> keys = attachments.stream()
                .map(Attachment::getStorageKey)
                .toList();
        fileStorageService.deleteFiles(keys);

        // Delete attachment records
        attachmentRepository.deleteByEntityTypeAndEntityId(entityType, entityId);
    }

    /**
     * Checks if an entity has any attachments.
     *
     * @param entityType The type of entity
     * @param entityId   The ID of the entity
     * @return true if attachments exist
     */
    @Transactional(readOnly = true)
    public boolean hasAttachments(String entityType, Long entityId) {
        return attachmentRepository.existsByEntityTypeAndEntityId(entityType, entityId);
    }

    /**
     * Counts attachments for an entity.
     *
     * @param entityType The type of entity
     * @param entityId   The ID of the entity
     * @return Number of attachments
     */
    @Transactional(readOnly = true)
    public long countAttachments(String entityType, Long entityId) {
        return attachmentRepository.countByEntityTypeAndEntityId(entityType, entityId);
    }
}

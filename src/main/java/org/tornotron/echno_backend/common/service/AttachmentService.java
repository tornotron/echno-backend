package org.tornotron.echno_backend.common.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.dto.StoredFile;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.issue.IssueRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.TaskRepository;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service for managing attachments across different modules.
 * Combines file storage operations with attachment record management.
 */
@Service
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final FileStorageService fileStorageService;
    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final IssueRepository issueRepository;
    private final UserRepository userRepository;

    public AttachmentService(AttachmentRepository attachmentRepository, FileStorageService fileStorageService, OrganizationRepository organizationRepository, ProjectRepository projectRepository, TaskRepository taskRepository, IssueRepository issueRepository, UserRepository userRepository) {
        this.attachmentRepository = attachmentRepository;
        this.fileStorageService = fileStorageService;
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.issueRepository = issueRepository;
        this.userRepository = userRepository;
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

        validateNoDuplicateFiles(files);
        validateNoExistingAttachments(files, entityType, entityId);

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
            switch (folder) {
                case "organization":
                    attachment.setOrganization(organizationRepository.findById(entityId).orElse(null));
                    break;

                case "project":
                case "projects":
                    attachment.setProject(projectRepository.findById(entityId).orElse(null));
                    break;

                case "task":
                    attachment.setTask(taskRepository.findById(entityId).orElse(null));
                    break;

                case "issue":
                    attachment.setIssue(issueRepository.findById(entityId).orElse(null));
                    break;

                case "user":
                    attachment.setUser(userRepository.findById(entityId).orElse(null));
                    break;
            }
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
        validateNoExistingAttachments(List.of(file), entityType, entityId);

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
                    dto.setId(attachment.getId());
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

    /**
     * Validates that the list contains no duplicate files.
     * A file is considered duplicate if it has the same filename AND size as another file.
     *
     * @param files The list of files to validate
     * @throws IllegalArgumentException if duplicate files are found
     */
    private void validateNoExistingAttachments(List<MultipartFile> files, String entityType, Long entityId) {
        List<String> existing = new ArrayList<>();
        for (MultipartFile file : files) {
            if (attachmentRepository.existsByEntityTypeAndEntityIdAndOriginalFilenameAndFileSize(
                    entityType, entityId, file.getOriginalFilename(), file.getSize())) {
                existing.add(file.getOriginalFilename());
            }
        }
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException("Attachments already exist: " + String.join(", ", existing));
        }
    }

    private void validateNoDuplicateFiles(List<MultipartFile> files) {
        Set<String> fileSignatures = new HashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (MultipartFile file : files) {
            String signature = file.getOriginalFilename() + "_" + file.getSize();
            if (!fileSignatures.add(signature)) {
                duplicates.add(file.getOriginalFilename());
            }
        }

        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("Duplicate files detected: " + String.join(", ", duplicates));
        }
    }
}

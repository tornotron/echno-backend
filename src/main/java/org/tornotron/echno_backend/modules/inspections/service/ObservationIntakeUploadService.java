package org.tornotron.echno_backend.modules.inspections.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.modules.inspections.ObservationEvidence;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationIntakeUpload;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeUploadPresignRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeUploadPresignResponse;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeUploadRegisterRequest;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationIntakeUploadRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Lets a machine producer upload the image it detected on before it posts the finding: a drone,
 * a fixed camera or a partner model holds no inspection to file the image against, and no manage
 * role. Same presigned path as inspection evidence, under owner type
 * {@value ObservationEvidence#INTAKE_UPLOAD_ENTITY_TYPE} and into a folder of the upload's own, so
 * a producer cannot file an object presigned for another upload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservationIntakeUploadService {

    static final int MAX_FILES = 20;
    static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final ObservationIntakeUploadRepository uploads;
    private final AttachmentService attachmentService;
    private final AttachmentMapper attachmentMapper;
    private final ProjectRepository projectRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    @Transactional
    public IntakeUploadPresignResponse presign(IntakeUploadPresignRequest req) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (!projectRepository.existsByIdAndOrganization_Id(req.projectId(), orgId)) {
            throw new ResourceNotFoundException("Project with ID " + req.projectId() + " was not found");
        }
        requireWithinLimit(req.uploads());
        for (UploadRequest file : req.uploads()) {
            requireAllowedFile(file.filename(), file.contentType(), file.fileSize());
        }
        ObservationIntakeUpload upload = new ObservationIntakeUpload();
        upload.setId(UUID.randomUUID());
        upload.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        upload.setProjectId(req.projectId());
        upload.setProducerSubject(currentSubject());
        uploads.save(upload);
        List<PresignedUpload> presigned = attachmentService.presignUploads(req.uploads(),
                ObservationEvidence.intakeUploadOwnerOf(upload.getId()), folderFor(orgId, upload.getId()));
        return new IntakeUploadPresignResponse(upload.getId(), presigned);
    }

    @Transactional
    public List<AttachmentDto> register(IntakeUploadRegisterRequest req) {
        ObservationIntakeUpload upload = uploads.findByIdScoped(req.uploadId())
                .filter(found -> found.getProducerSubject().equals(currentSubject()))
                .orElseThrow(() -> new ResourceNotFoundException("Upload with ID " + req.uploadId() + " was not found"));
        requireWithinLimit(req.uploads());
        String folder = folderFor(TenantContext.getCurrentOrgId(), upload.getId());
        Set<String> keys = new HashSet<>();
        for (RegisterUploadRequest file : req.uploads()) {
            requireKeyUnder(folder, file.key());
            if (!keys.add(file.key())) {
                throw new InvalidRequestException("Storage key '" + file.key() + "' is listed twice");
            }
            requireAllowedFile(file.filename(), file.contentType(), file.fileSize());
        }
        AttachmentOwner owner = ObservationEvidence.intakeUploadOwnerOf(upload.getId());
        Project project = projectRepository.getReferenceById(upload.getProjectId());
        List<Attachment> stored = attachmentService.registerUploads(req.uploads(), owner, folder);
        stored.forEach(attachment -> attachment.setProject(project));
        log.info("Registered {} intake uploads under {} on project {}", stored.size(), upload.getId(),
                upload.getProjectId());
        return stored.stream().map(attachmentMapper::toDto).toList();
    }

    /** {@code observations/intake/<orgId>/<uploadId>}; a registered key must sit under it. */
    static String folderFor(Long orgId, UUID uploadId) {
        return "observations/intake/" + orgId + "/" + uploadId;
    }

    private String currentSubject() {
        String subject = userContextService.getCurrentKeycloakId();
        if (subject == null) {
            throw new InvalidRequestException("The caller has no token subject");
        }
        return subject;
    }

    private static void requireWithinLimit(List<?> files) {
        if (files.size() > MAX_FILES) {
            throw new InvalidRequestException("At most " + MAX_FILES + " files per call");
        }
    }

    private static void requireAllowedFile(String filename, String contentType, Long fileSize) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(type)) {
            throw new InvalidRequestException("File '" + filename + "' is " + contentType
                    + "; a producer uploads image/jpeg, image/png or image/webp");
        }
        if (fileSize == null || fileSize <= 0) {
            throw new InvalidRequestException("File '" + filename + "' must declare its size");
        }
        if (fileSize > MAX_FILE_BYTES) {
            throw new InvalidRequestException("File '" + filename + "' is over the 25 MB limit");
        }
    }

    private static void requireKeyUnder(String folder, String key) {
        if (key == null || !key.startsWith(folder + "/") || key.contains("/../")) {
            throw new InvalidRequestException("Storage key '" + key + "' was not presigned for this upload");
        }
    }
}

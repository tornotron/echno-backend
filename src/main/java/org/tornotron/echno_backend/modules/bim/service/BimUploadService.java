package org.tornotron.echno_backend.modules.bim.service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.bim.BimStorageLayout;
import org.tornotron.echno_backend.modules.bim.BimVersionStatus;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimModelVersionDto;
import org.tornotron.echno_backend.modules.bim.dto.BimSourceUploadDto;
import org.tornotron.echno_backend.modules.bim.dto.PresignBimSourceRequest;
import org.tornotron.echno_backend.modules.bim.mapper.BimMapper;
import org.tornotron.echno_backend.modules.bim.repository.BimModelVersionRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The source IFC never touches the API. Presign creates the next version and hands the
 * browser a PUT for {@code bim/<modelId>/<versionId>/source.ifc}; register confirms the object
 * is really there, files it as an attachment of the model (owner {@code BIM_MODEL}, the same
 * path every other upload takes) and queues the worker.
 */
@Service
@RequiredArgsConstructor
public class BimUploadService {

    static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(60);
    static final String DEFAULT_CONTENT_TYPE = "application/x-step";
    static final String FOLDER = "bim";

    private final BimModelService modelService;
    private final BimModelVersionRepository versions;
    private final FileStorageService fileStorageService;
    private final AttachmentService attachmentService;
    private final UserContextService userContextService;
    private final BimMapper mapper;

    @Value("${echno.modules.bim.max-source-bytes:1073741824}")
    private long maxSourceBytes;

    @Transactional
    public BimSourceUploadDto presignSource(UUID modelId, PresignBimSourceRequest req) {
        BimModel model = modelService.requireModel(modelId);
        String filename = req.filename().trim();
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".ifc") || lower.endsWith(".ifczip") || lower.endsWith(".ifcxml"))) {
            throw new InvalidRequestException("Only IFC files (.ifc, .ifczip, .ifcxml) can be uploaded as a model version");
        }
        if (req.fileSize() > maxSourceBytes) {
            throw new InvalidRequestException("The IFC is " + mb(req.fileSize()) + " MB; the cap is "
                    + mb(maxSourceBytes) + " MB. Split the model by building and upload each as its own model.");
        }
        BimModelVersion version = new BimModelVersion();
        version.setOrganization(model.getOrganization());
        version.setModelId(modelId);
        version.setProjectId(model.getProjectId());
        version.setVersionNumber(versions.maxVersionNumber(modelId) + 1);
        version.setStatus(BimVersionStatus.UPLOADED);
        version.setSourceFilename(filename);
        version.setSourceSizeBytes(req.fileSize());
        version.setUploadedById(userContextService.getCurrentUserId());
        // The key needs the id, which is assigned at persist.
        version.setSourceKey("pending");
        version = versions.saveAndFlush(version);
        version.setSourceKey(BimStorageLayout.sourceKey(modelId, version.getId()));
        versions.save(version);

        String contentType = req.contentType() == null || req.contentType().isBlank()
                ? DEFAULT_CONTENT_TYPE : req.contentType();
        PresignedUpload upload = fileStorageService.generateUploadUrlForKey(version.getSourceKey(), contentType,
                UPLOAD_URL_EXPIRY);
        return new BimSourceUploadDto(version.getId(), version.getVersionNumber(), upload);
    }

    @Transactional
    public BimModelVersionDto registerSource(UUID modelId, UUID versionId) {
        modelService.requireModel(modelId);
        BimModelVersion version = modelService.requireVersion(modelId, versionId);
        if (version.getStatus() != BimVersionStatus.UPLOADED) {
            throw new InvalidRequestException("Version " + version.getVersionNumber() + " is already " + version.getStatus());
        }
        if (!fileStorageService.objectExists(version.getSourceKey())) {
            throw new InvalidRequestException("No object was found at " + version.getSourceKey()
                    + "; the upload may not have completed");
        }
        attachmentService.registerUploads(
                List.of(new RegisterUploadRequest(version.getSourceKey(), version.getSourceFilename(),
                        DEFAULT_CONTENT_TYPE, version.getSourceSizeBytes())),
                BimStorageLayout.ownerOf(modelId), FOLDER);
        modelService.enqueueImport(modelId, versionId);
        return mapper.toDto(modelService.requireVersion(modelId, versionId));
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }
}

package org.tornotron.echno_backend.modules.bim.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.bim.BimImportJobStatus;
import org.tornotron.echno_backend.modules.bim.BimStorageLayout;
import org.tornotron.echno_backend.modules.bim.BimVersionStatus;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.domain.BimImportJob;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimElementDto;
import org.tornotron.echno_backend.modules.bim.dto.BimElementPageDto;
import org.tornotron.echno_backend.modules.bim.dto.BimImportJobDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelVersionDto;
import org.tornotron.echno_backend.modules.bim.dto.CreateBimModelRequest;
import org.tornotron.echno_backend.modules.bim.repository.BimElementRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimImportJobRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimModelRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimModelVersionRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Models, versions, elements and jobs as the API sees them. Every read goes through a
 * scoped query so a foreign id reads as absent; every write stamps the current tenant.
 */
@Service
@RequiredArgsConstructor
public class BimModelService {

    private final BimModelRepository models;
    private final BimModelVersionRepository versions;
    private final BimElementRepository elements;
    private final BimImportJobRepository jobs;
    private final ProjectRepository projectRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;

    @Transactional
    public BimModelDto create(Long projectId, CreateBimModelRequest req) {
        requireProject(projectId);
        String name = req.name().trim();
        if (models.existsByProjectIdAndNameIgnoreCase(projectId, name)) {
            throw new DuplicateResourceException("A BIM model named '" + name + "' already exists on this project");
        }
        BimModel m = new BimModel();
        m.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        m.setProjectId(projectId);
        m.setName(name);
        m.setDescription(req.description());
        m.setCreatedBy(userContextService.getCurrentUserId());
        m.setUpdatedBy(m.getCreatedBy());
        return BimMapper.toDto(models.save(m), List.of());
    }

    @Transactional(readOnly = true)
    public List<BimModelDto> listForProject(Long projectId) {
        requireProject(projectId);
        return models.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(m -> BimMapper.toDto(m, versionDtos(m.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public BimModelDto get(UUID modelId) {
        BimModel m = requireModel(modelId);
        return BimMapper.toDto(m, versionDtos(m.getId()));
    }

    @Transactional(readOnly = true)
    public BimModelVersionDto getVersion(UUID modelId, UUID versionId) {
        requireModel(modelId);
        return BimMapper.toDto(requireVersion(modelId, versionId));
    }

    @Transactional(readOnly = true)
    public BimElementPageDto listElements(UUID modelId, String storeyGlobalId, boolean includeRetired,
                                          int page, int size) {
        requireModel(modelId);
        Page<BimElement> found = elements.search(modelId, blankToNull(storeyGlobalId), includeRetired,
                PageRequest.of(page, Math.min(Math.max(size, 1), 500)));
        List<BimElementDto> content = found.getContent().stream().map(BimMapper::toDto).toList();
        return new BimElementPageDto(content, found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public BimElementDto getElement(UUID elementId) {
        return BimMapper.toDto(requireElement(elementId));
    }

    @Transactional(readOnly = true)
    public List<BimImportJobDto> listJobs(UUID modelId, UUID versionId) {
        requireModel(modelId);
        requireVersion(modelId, versionId);
        return jobs.findByVersionIdOrderByQueuedAtDesc(versionId).stream().map(BimMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public BimImportJobDto getJob(UUID jobId) {
        return BimMapper.toDto(jobs.findByIdScoped(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("BIM import job not found: " + jobId)));
    }

    /**
     * Queues the worker job for a version that has its source file in place. The version
     * follows the job into QUEUED; a FAILED version may be queued again, which is the retry.
     */
    @Transactional
    public BimImportJobDto enqueueImport(UUID modelId, UUID versionId) {
        BimModel model = requireModel(modelId);
        BimModelVersion version = requireVersion(modelId, versionId);
        jobs.findFirstByVersionIdOrderByQueuedAtDesc(versionId)
                .filter(j -> !j.getStatus().isTerminal())
                .ifPresent(j -> {
                    throw new InvalidRequestException("An import job is already " + j.getStatus()
                            + " for version " + versionId);
                });
        version.transitionTo(BimVersionStatus.QUEUED);
        version.setError(null);

        BimImportJob job = new BimImportJob();
        job.setOrganization(model.getOrganization());
        job.setModelId(modelId);
        job.setVersionId(versionId);
        job.setSourceKey(version.getSourceKey());
        job.setOutputPrefix(BimStorageLayout.prefix(modelId, versionId));
        job.setStatus(BimImportJobStatus.QUEUED);
        job.setQueuedAt(LocalDateTime.now());
        return BimMapper.toDto(jobs.save(job));
    }

    // ------------------------------------------------------------------------------------
    // Lookups shared with the other BIM services
    // ------------------------------------------------------------------------------------

    BimModel requireModel(UUID modelId) {
        return models.findByIdScoped(modelId)
                .orElseThrow(() -> new ResourceNotFoundException("BIM model not found: " + modelId));
    }

    BimModelVersion requireVersion(UUID modelId, UUID versionId) {
        return versions.findByIdAndModelId(versionId, modelId)
                .orElseThrow(() -> new ResourceNotFoundException("BIM model version not found: " + versionId));
    }

    BimElement requireElement(UUID elementId) {
        return elements.findByIdScoped(elementId)
                .orElseThrow(() -> new ResourceNotFoundException("BIM element not found: " + elementId));
    }

    void requireProject(Long projectId) {
        if (projectId == null || !projectRepository.existsByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Project not found: " + projectId);
        }
    }

    private List<BimModelVersionDto> versionDtos(UUID modelId) {
        return versions.findByModelIdOrderByVersionNumberDesc(modelId).stream().map(BimMapper::toDto).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

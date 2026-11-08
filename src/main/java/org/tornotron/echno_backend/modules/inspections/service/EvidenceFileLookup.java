package org.tornotron.echno_backend.modules.inspections.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.modules.inspections.InspectionEvidence;
import org.tornotron.echno_backend.modules.inspections.ObservationEvidence;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFile;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFiles;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceImageRegistered;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;

/**
 * Evidence files as other modules see them, and the {@link EvidenceImageRegistered} event the two
 * evidence services publish when an image is stored. Reads are pinned to the current tenant by
 * organization id on every query, not only by the session filter.
 */
@Service
@RequiredArgsConstructor
public class EvidenceFileLookup implements EvidenceFiles {

    private final AttachmentRepository attachments;
    private final InspectionRepository inspections;
    private final ObservationRepository observations;
    private final ApplicationEventPublisher publisher;

    @Override
    @Transactional(readOnly = true)
    public List<EvidenceFile> ofInspection(UUID inspectionId) {
        Long projectId = inspections.findProjectIdByIdScoped(inspectionId).orElseGet(() -> {
            if (inspections.findByIdScoped(inspectionId).isEmpty()) {
                throw new ResourceNotFoundException("Inspection with ID " + inspectionId + " was not found");
            }
            return null;
        });
        return owned(InspectionEvidence.ENTITY_TYPE, inspectionId).stream()
                .map(a -> toFile(a, projectId, inspectionId, null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvidenceFile> ofObservation(UUID observationId) {
        Long projectId = observations.findProjectIdByIdScoped(observationId).orElseGet(() -> {
            if (observations.findByIdScoped(observationId).isEmpty()) {
                throw new ResourceNotFoundException("Observation with ID " + observationId + " was not found");
            }
            return null;
        });
        return owned(ObservationEvidence.ENTITY_TYPE, observationId).stream()
                .map(a -> toFile(a, projectId, null, observationId))
                .toList();
    }

    /** {@inheritDoc} Evidence of an inspection or observation with no project is not returned. */
    @Override
    @Transactional(readOnly = true)
    public Optional<EvidenceFile> find(Long attachmentId) {
        if (attachmentId == null) {
            return Optional.empty();
        }
        return attachments.findByIdAndOrganization_Id(attachmentId, TenantContext.getCurrentOrgId())
                .filter(a -> a.getEntityUuid() != null)
                .flatMap(a -> switch (a.getEntityType()) {
                    case InspectionEvidence.ENTITY_TYPE -> inspections.findProjectIdByIdScoped(a.getEntityUuid())
                            .map(projectId -> toFile(a, projectId, a.getEntityUuid(), null));
                    case ObservationEvidence.ENTITY_TYPE -> observations.findProjectIdByIdScoped(a.getEntityUuid())
                            .map(projectId -> toFile(a, projectId, null, a.getEntityUuid()));
                    default -> Optional.empty();
                });
    }

    /** Publishes one event per stored image filed against an inspection. */
    public void inspectionImagesStored(UUID inspectionId, Long projectId, List<AttachmentDto> stored) {
        publishImages(stored, a -> toFile(a, projectId, inspectionId, null));
    }

    /** Publishes one event per stored image filed against an observation. */
    public void observationImagesStored(UUID observationId, Long projectId, List<AttachmentDto> stored) {
        publishImages(stored, a -> toFile(a, projectId, null, observationId));
    }

    private void publishImages(List<AttachmentDto> stored, Function<Attachment, EvidenceFile> toFile) {
        Long orgId = TenantContext.getCurrentOrgId();
        for (AttachmentDto dto : stored) {
            if (!isImage(dto.getContentType())) {
                continue;
            }
            attachments.findByIdAndOrganization_Id(dto.getId(), orgId)
                    .ifPresent(a -> publisher.publishEvent(new EvidenceImageRegistered(toFile.apply(a))));
        }
    }

    private List<Attachment> owned(String entityType, UUID ownerId) {
        Long orgId = TenantContext.getCurrentOrgId();
        return attachments.findByEntityTypeAndEntityUuidOrderByIdAsc(entityType, ownerId).stream()
                .filter(a -> a.getOrganization() != null && a.getOrganization().getId().equals(orgId))
                .toList();
    }

    private static boolean isImage(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private static EvidenceFile toFile(Attachment a, Long projectId, UUID inspectionId, UUID observationId) {
        return new EvidenceFile(a.getOrganization() == null ? null : a.getOrganization().getId(), projectId,
                inspectionId, observationId, a.getId(), a.getStorageKey(), a.getContentType(), a.getCreatedAt());
    }
}

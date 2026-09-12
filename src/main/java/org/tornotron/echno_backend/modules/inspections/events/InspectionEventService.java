package org.tornotron.echno_backend.modules.inspections.events;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionEventDto;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionEventFilter;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionEventRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.NcrRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ReinspectionRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The read side of the event log. Every read is organization-explicit on top of the
 * {@code orgFilter}, and a timeline for a record the tenant does not hold is a 404 rather than
 * an empty page, so a guessed id learns nothing.
 */
@Service
@RequiredArgsConstructor
public class InspectionEventService {

    private final InspectionEventRepository events;
    private final InspectionRepository inspectionRepo;
    private final NcrRepository ncrRepo;
    private final ReinspectionRepository reinspectionRepo;

    /** The whole timeline of one inspection: itself, its items, defects, NCRs and reinspections. */
    @Transactional(readOnly = true)
    public Page<InspectionEventDto> inspectionTimeline(UUID inspectionId, Pageable pageable) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (!inspectionRepo.existsByIdAndOrganization_Id(inspectionId, orgId)) {
            throw new ResourceNotFoundException("Inspection with ID " + inspectionId + " was not found");
        }
        return events.search(orgId,
                        InspectionEventFilter.forInspection(inspectionId), pageable)
                .map(InspectionEventService::toDto);
    }

    /** The timeline of one NCR, and of the reinspections raised on it once those exist. */
    @Transactional(readOnly = true)
    public Page<InspectionEventDto> ncrTimeline(UUID ncrId, Pageable pageable) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (!ncrRepo.existsByIdAndOrganization_Id(ncrId, orgId)) {
            throw new ResourceNotFoundException("NCR with ID " + ncrId + " was not found");
        }
        List<UUID> subjects = new ArrayList<>();
        subjects.add(ncrId);
        subjects.addAll(reinspectionRepo.findIdsByNcrIdAndOrganizationId(ncrId, orgId));
        return events.search(orgId,
                        new InspectionEventFilter(null, null, subjects, null, null, null, null, null),
                        pageable)
                .map(InspectionEventService::toDto);
    }

    /** The project-wide log, narrowed by whatever the caller names. */
    @Transactional(readOnly = true)
    public Page<InspectionEventDto> search(Long projectId,
                                           InspectionEventSubjectType subjectType,
                                           String eventType,
                                           String actorId,
                                           LocalDateTime from,
                                           LocalDateTime to,
                                           Pageable pageable) {
        return events.search(TenantContext.getCurrentOrgId(),
                        new InspectionEventFilter(null, subjectType, null, projectId, eventType,
                                actorId, from, to),
                        pageable)
                .map(InspectionEventService::toDto);
    }

    static InspectionEventDto toDto(InspectionEvent e) {
        return new InspectionEventDto(e.getId(), e.getSubjectType(), e.getSubjectId(),
                e.getInspectionId(), e.getProjectId(), e.getEventType(), e.getActorType(),
                e.getActorId(), e.getOccurredAt(), e.getBefore(), e.getAfter(), e.getNote(),
                e.getRequestId());
    }
}

package org.tornotron.echno_backend.modules.toolboxtalks.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.toolboxtalks.api.ToolboxTalkRecordedEvent;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalk;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkPhotos;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.CreateToolboxTalkRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkDto;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.UpdateToolboxTalkRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.mapper.ToolboxTalksMapper;
import org.tornotron.echno_backend.modules.toolboxtalks.repository.ToolboxTalkRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.spatial.SpatialNodeRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Toolbox talks as the API sees them. Every read goes through a scoped query so a foreign id
 * reads as absent; every write stamps the current tenant and user.
 *
 * <p>The rules, in one place: the project, the conductor and every attendee belong to the
 * caller's organization and the people are active employees; a talk is dated no further
 * ahead than tomorrow, so a crew can be briefed the evening before; only a draft changes;
 * recording needs at least one attendee and happens once.
 */
@Service
@RequiredArgsConstructor
public class ToolboxTalksService {

    static final int MAX_DAYS_AHEAD = 1;

    private final ToolboxTalkRepository talks;
    private final ProjectRepository projects;
    private final SpatialNodeRepository spatialNodes;
    private final EmployeeRepository employees;
    private final AttachmentService attachmentService;
    private final AttachmentMapper attachmentMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final ToolboxTalksMapper mapper;
    private final ApplicationEventPublisher events;

    @Transactional
    public ToolboxTalkDto create(CreateToolboxTalkRequest req) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        requireProject(org, req.projectId());
        requireSpatialNode(req.projectId(), req.spatialNodeId());
        requireTalkDate(req.talkDate());
        Set<Long> people = new LinkedHashSet<>();
        people.add(req.conductorEmployeeId());
        if (req.attendeeEmployeeIds() != null) {
            people.addAll(req.attendeeEmployeeIds());
        }
        requireActiveEmployees(org, people);

        ToolboxTalk talk = new ToolboxTalk();
        talk.setOrganization(org);
        talk.setProjectId(req.projectId());
        talk.setSpatialNodeId(req.spatialNodeId());
        talk.setTopic(req.topic().trim());
        talk.setTalkDate(req.talkDate());
        talk.setTalkTime(req.talkTime());
        talk.setConductorEmployeeId(req.conductorEmployeeId());
        talk.setNotes(req.notes());
        if (req.attendeeEmployeeIds() != null) {
            req.attendeeEmployeeIds().forEach(talk::addAttendee);
        }
        talk.setCreatedBy(userContextService.getCurrentUserId());
        talk.setUpdatedBy(talk.getCreatedBy());
        return mapper.toDto(talks.save(talk));
    }

    @Transactional
    public ToolboxTalkDto update(UUID id, UpdateToolboxTalkRequest req) {
        ToolboxTalk talk = requireDraft(id);
        requireSpatialNode(talk.getProjectId(), req.spatialNodeId());
        requireTalkDate(req.talkDate());
        requireActiveEmployees(talk.getOrganization(), Set.of(req.conductorEmployeeId()));

        talk.setSpatialNodeId(req.spatialNodeId());
        talk.setTopic(req.topic().trim());
        talk.setTalkDate(req.talkDate());
        talk.setTalkTime(req.talkTime());
        talk.setConductorEmployeeId(req.conductorEmployeeId());
        talk.setNotes(req.notes());
        talk.setUpdatedBy(userContextService.getCurrentUserId());
        return mapper.toDto(talks.save(talk));
    }

    @Transactional
    public ToolboxTalkDto addAttendees(UUID id, List<Long> employeeIds) {
        ToolboxTalk talk = requireDraft(id);
        requireActiveEmployees(talk.getOrganization(), new LinkedHashSet<>(employeeIds));
        employeeIds.forEach(talk::addAttendee);
        talk.setUpdatedBy(userContextService.getCurrentUserId());
        return mapper.toDto(talks.save(talk));
    }

    @Transactional
    public ToolboxTalkDto removeAttendee(UUID id, Long employeeId) {
        ToolboxTalk talk = requireDraft(id);
        if (!talk.removeAttendee(employeeId)) {
            throw new ResourceNotFoundException("Employee " + employeeId + " is not on the attendance of talk " + id);
        }
        talk.setUpdatedBy(userContextService.getCurrentUserId());
        return mapper.toDto(talks.save(talk));
    }

    /**
     * Signs the talk off. The attendance is the point of the record, so an empty one cannot be
     * recorded; a recorded talk is the safety record and is never edited again.
     */
    @Transactional
    public ToolboxTalkDto record(UUID id) {
        ToolboxTalk talk = requireDraft(id);
        if (talk.getAttendees().isEmpty()) {
            throw new InvalidRequestException("A toolbox talk needs at least one attendee before it is recorded");
        }
        talk.setStatus(ToolboxTalkStatus.RECORDED);
        talk.setRecordedAt(LocalDateTime.now());
        talk.setUpdatedBy(userContextService.getCurrentUserId());
        ToolboxTalk recorded = talks.save(talk);
        events.publishEvent(new ToolboxTalkRecordedEvent(recorded.getOrganization().getId(), recorded.getId(),
                recorded.getProjectId(), recorded.getTalkDate(), recorded.getConductorEmployeeId(),
                recorded.getAttendees().size()));
        return mapper.toDto(recorded);
    }

    @Transactional(readOnly = true)
    public Page<ToolboxTalkDto> list(Long projectId, LocalDate from, LocalDate to, ToolboxTalkStatus status,
                                     int page, int size) {
        return talks.findPage(projectId, from, to, status, PageRequest.of(page, size)).map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public ToolboxTalkDto get(UUID id) {
        return mapper.toDto(require(id));
    }

    // Photo evidence rides on the platform's attachment store through the direct-to-storage
    // path: presign, upload from the phone, register. The keys are checked back against the
    // talk's own folder so a registration cannot claim an object presigned for something else.

    @Transactional(readOnly = true)
    public List<AttachmentDto> listPhotos(UUID id) {
        require(id);
        return attachmentService.getAttachments(ToolboxTalkPhotos.ENTITY_TYPE, id);
    }

    @Transactional(readOnly = true)
    public List<PresignedUpload> presignPhotos(UUID id, List<UploadRequest> uploads) {
        require(id);
        AttachmentOwner owner = ToolboxTalkPhotos.ownerOf(id);
        return attachmentService.presignUploads(uploads, owner, ToolboxTalkPhotos.folderFor(id));
    }

    @Transactional
    public List<AttachmentDto> registerPhotos(UUID id, List<RegisterUploadRequest> uploads) {
        require(id);
        String folder = ToolboxTalkPhotos.folderFor(id);
        String prefix = folder + "/";
        for (RegisterUploadRequest upload : uploads == null ? List.<RegisterUploadRequest>of() : uploads) {
            String key = upload.key();
            if (key == null || !key.startsWith(prefix) || key.contains("/../")) {
                throw new InvalidRequestException("Storage key '" + key + "' was not presigned for this talk");
            }
        }
        return attachmentService.registerUploads(uploads, ToolboxTalkPhotos.ownerOf(id), folder)
                .stream()
                .map(attachmentMapper::toDto)
                .toList();
    }

    // ---------------------------------------------------------------- rules

    private ToolboxTalk require(UUID id) {
        return talks.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Toolbox talk not found: " + id));
    }

    private ToolboxTalk requireDraft(UUID id) {
        ToolboxTalk talk = require(id);
        if (!talk.isDraft()) {
            throw new InvalidRequestException("Toolbox talk " + id + " is recorded and no longer changes");
        }
        return talk;
    }

    private void requireProject(Organization org, Long projectId) {
        projects.findByIdAndOrganization_Id(projectId, org.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    private void requireSpatialNode(Long projectId, UUID spatialNodeId) {
        if (spatialNodeId == null) {
            return;
        }
        spatialNodes.findByIdAndProjectId(spatialNodeId, projectId)
                .orElseThrow(() -> new InvalidRequestException(
                        "Spatial node " + spatialNodeId + " does not belong to project " + projectId));
    }

    static void requireTalkDate(LocalDate talkDate) {
        if (talkDate.isAfter(LocalDate.now().plusDays(MAX_DAYS_AHEAD))) {
            throw new InvalidRequestException(
                    "A toolbox talk is dated no more than " + MAX_DAYS_AHEAD + " day ahead; " + talkDate + " is too far out");
        }
    }

    private void requireActiveEmployees(Organization org, Set<Long> employeeIds) {
        if (employeeIds.isEmpty()) {
            return;
        }
        List<Employee> found = employees.findAllByIdInAndOrganizationId(employeeIds, org.getId());
        Set<Long> active = new LinkedHashSet<>();
        for (Employee employee : found) {
            if (employee.getStatus() == EmployeeStatus.active) {
                active.add(employee.getId());
            }
        }
        for (Long id : employeeIds) {
            if (!active.contains(id)) {
                throw new InvalidRequestException(
                        "Employee " + id + " is not an active employee of this organization");
            }
        }
    }
}

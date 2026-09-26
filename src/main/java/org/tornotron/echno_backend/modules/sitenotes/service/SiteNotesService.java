package org.tornotron.echno_backend.modules.sitenotes.service;

import java.time.LocalDate;
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
import org.tornotron.echno_backend.modules.sitenotes.api.SiteNoteAddedEvent;
import org.tornotron.echno_backend.modules.sitenotes.domain.SiteNote;
import org.tornotron.echno_backend.modules.sitenotes.domain.SiteNotePhotos;
import org.tornotron.echno_backend.modules.sitenotes.dto.CreateSiteNoteRequest;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNoteDto;
import org.tornotron.echno_backend.modules.sitenotes.dto.UpdateSiteNoteRequest;
import org.tornotron.echno_backend.modules.sitenotes.mapper.SiteNotesMapper;
import org.tornotron.echno_backend.modules.sitenotes.repository.SiteNoteRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Site notes as the API sees them. Every read goes through a scoped query so a foreign id
 * reads as absent; every write stamps the current tenant and user.
 *
 * <p>The rules, in one place: the project belongs to the caller's organization, the author is an
 * active employee of it, and a note is dated no further ahead than tomorrow, so a supervisor can
 * write up the evening before.
 */
@Service
@RequiredArgsConstructor
public class SiteNotesService {

    static final int MAX_DAYS_AHEAD = 1;

    private final SiteNoteRepository notes;
    private final ProjectRepository projects;
    private final EmployeeRepository employees;
    private final AttachmentService attachmentService;
    private final AttachmentMapper attachmentMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final SiteNotesMapper mapper;
    private final ApplicationEventPublisher events;

    @Transactional
    public SiteNoteDto create(CreateSiteNoteRequest req) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        requireProject(org, req.projectId());
        requireNoteDate(req.noteDate());
        requireActiveEmployee(org, req.authorEmployeeId());

        SiteNote note = new SiteNote();
        note.setOrganization(org);
        note.setProjectId(req.projectId());
        note.setNoteDate(req.noteDate());
        note.setAuthorEmployeeId(req.authorEmployeeId());
        note.setNote(req.note().trim());
        note.setCreatedBy(userContextService.getCurrentUserId());
        note.setUpdatedBy(note.getCreatedBy());
        SiteNote saved = notes.save(note);
        events.publishEvent(new SiteNoteAddedEvent(org.getId(), saved.getId(), saved.getProjectId(),
                saved.getNoteDate(), saved.getAuthorEmployeeId()));
        return mapper.toDto(saved);
    }

    @Transactional
    public SiteNoteDto update(UUID id, UpdateSiteNoteRequest req) {
        SiteNote note = require(id);
        requireNoteDate(req.noteDate());
        note.setNoteDate(req.noteDate());
        note.setNote(req.note().trim());
        note.setUpdatedBy(userContextService.getCurrentUserId());
        return mapper.toDto(notes.save(note));
    }

    @Transactional(readOnly = true)
    public Page<SiteNoteDto> list(Long projectId, LocalDate from, LocalDate to, int page, int size) {
        return notes.findPage(projectId, from, to, PageRequest.of(page, size)).map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public SiteNoteDto get(UUID id) {
        return mapper.toDto(require(id));
    }

    // The optional photo rides on the platform's attachment store through the direct-to-storage
    // path: presign, upload from the phone, register. The keys are checked back against the
    // note's own folder so a registration cannot claim an object presigned for something else.

    @Transactional(readOnly = true)
    public List<AttachmentDto> listPhotos(UUID id) {
        require(id);
        return attachmentService.getAttachments(SiteNotePhotos.ENTITY_TYPE, id);
    }

    @Transactional(readOnly = true)
    public List<PresignedUpload> presignPhotos(UUID id, List<UploadRequest> uploads) {
        require(id);
        AttachmentOwner owner = SiteNotePhotos.ownerOf(id);
        return attachmentService.presignUploads(uploads, owner, SiteNotePhotos.folderFor(id));
    }

    @Transactional
    public List<AttachmentDto> registerPhotos(UUID id, List<RegisterUploadRequest> uploads) {
        require(id);
        String folder = SiteNotePhotos.folderFor(id);
        String prefix = folder + "/";
        for (RegisterUploadRequest upload : uploads == null ? List.<RegisterUploadRequest>of() : uploads) {
            String key = upload.key();
            if (key == null || !key.startsWith(prefix) || key.contains("/../")) {
                throw new InvalidRequestException("Storage key '" + key + "' was not presigned for this note");
            }
        }
        return attachmentService.registerUploads(uploads, SiteNotePhotos.ownerOf(id), folder)
                .stream()
                .map(attachmentMapper::toDto)
                .toList();
    }

    // ---------------------------------------------------------------- rules

    private SiteNote require(UUID id) {
        return notes.findByIdScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Site note not found: " + id));
    }

    // A project of another tenant is a 404, the same answer as a project that does not exist.
    private void requireProject(Organization org, Long projectId) {
        projects.findByIdAndOrganization_Id(projectId, org.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    static void requireNoteDate(LocalDate noteDate) {
        if (noteDate.isAfter(LocalDate.now().plusDays(MAX_DAYS_AHEAD))) {
            throw new InvalidRequestException(
                    "A site note is dated no more than " + MAX_DAYS_AHEAD + " day ahead; " + noteDate + " is too far out");
        }
    }

    // An employee of another organization and an inactive one of ours get the same 400, so the
    // message leaks nothing about the other tenant.
    private void requireActiveEmployee(Organization org, Long employeeId) {
        List<Employee> found = employees.findAllByIdInAndOrganizationId(Set.of(employeeId), org.getId());
        boolean active = found.stream().anyMatch(e -> e.getStatus() == EmployeeStatus.active);
        if (!active) {
            throw new InvalidRequestException(
                    "Employee " + employeeId + " is not an active employee of this organization");
        }
    }
}

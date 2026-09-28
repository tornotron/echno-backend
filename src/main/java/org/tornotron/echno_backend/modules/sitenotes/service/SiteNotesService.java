package org.tornotron.echno_backend.modules.sitenotes.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
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
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
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
import org.tornotron.echno_backend.modules.sitenotes.time.SiteNotesClock;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Site notes as the API sees them. Every read goes through a scoped query so a foreign id
 * reads as absent; every write stamps the current tenant and user.
 *
 * <p>The rules, in one place: the project belongs to the caller's organization; the author is the
 * caller's own employee record, never a request field, so a manage role cannot file a note in a
 * colleague's name; a note is dated no further ahead than tomorrow and no further back than
 * {@value #MAX_DAYS_BEHIND} days, so a supervisor can write up the evening before but cannot
 * backdate an old record either; only the author or a system admin may change a note afterward.
 */
@Service
public class SiteNotesService {

    static final int MAX_DAYS_AHEAD = 1;

    // ponytail: a round number, not a business rule handed down; a note is meant to be a
    // contemporaneous record, and this just keeps someone from backdating one into last year.
    // Raise it (or make it a property) if a real need for older entries turns up.
    static final int MAX_DAYS_BEHIND = 60;

    // "An optional photo", singular: one image per note, not a gallery.
    private static final int MAX_PHOTOS = 1;
    private static final String IMAGE_CONTENT_TYPE_PREFIX = "image/";

    private final SiteNoteRepository notes;
    private final ProjectRepository projects;
    private final EmployeeRepository employees;
    private final AttachmentService attachmentService;
    private final AttachmentMapper attachmentMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final UserContextService userContextService;
    private final CurrentEmployeeService currentEmployeeService;
    private final OrganizationSecurityService orgSecurity;
    private final SiteNotesMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SiteNotesService(SiteNoteRepository notes,
                             ProjectRepository projects,
                             EmployeeRepository employees,
                             AttachmentService attachmentService,
                             AttachmentMapper attachmentMapper,
                             TenantEntityHelper tenantEntityHelper,
                             UserContextService userContextService,
                             CurrentEmployeeService currentEmployeeService,
                             OrganizationSecurityService orgSecurity,
                             SiteNotesMapper mapper,
                             ApplicationEventPublisher events,
                             @SiteNotesClock Clock clock) {
        this.notes = notes;
        this.projects = projects;
        this.employees = employees;
        this.attachmentService = attachmentService;
        this.attachmentMapper = attachmentMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.userContextService = userContextService;
        this.currentEmployeeService = currentEmployeeService;
        this.orgSecurity = orgSecurity;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public SiteNoteDto create(CreateSiteNoteRequest req) {
        Organization org = tenantEntityHelper.resolveCurrentOrganization();
        Employee author = currentEmployeeService.requireCurrentEmployee("add a site note");
        requireProject(org, req.projectId());
        requireNoteDate(req.noteDate());
        requireActiveEmployee(org, author.getId());

        SiteNote note = new SiteNote();
        note.setOrganization(org);
        note.setProjectId(req.projectId());
        note.setNoteDate(req.noteDate());
        note.setAuthorEmployeeId(author.getId());
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
        requireAuthorOrAdmin(note);
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
        List<UploadRequest> declared = uploads == null ? List.<UploadRequest>of() : uploads;
        requirePhotoRoom(id, declared.size());
        requireImageUploads(declared);
        AttachmentOwner owner = SiteNotePhotos.ownerOf(id);
        return attachmentService.presignUploads(declared, owner, SiteNotePhotos.folderFor(id));
    }

    @Transactional
    public List<AttachmentDto> registerPhotos(UUID id, List<RegisterUploadRequest> uploads) {
        require(id);
        List<RegisterUploadRequest> declared = uploads == null ? List.<RegisterUploadRequest>of() : uploads;
        requirePhotoRoom(id, declared.size());
        requireImageRegistrations(declared);
        String folder = SiteNotePhotos.folderFor(id);
        String prefix = folder + "/";
        for (RegisterUploadRequest upload : declared) {
            String key = upload.key();
            if (key == null || !key.startsWith(prefix) || key.contains("/../")) {
                throw new InvalidRequestException("Storage key '" + key + "' was not presigned for this note");
            }
        }
        return attachmentService.registerUploads(declared, SiteNotePhotos.ownerOf(id), folder)
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

    // "Tomorrow" and "too old" are the site's, not the server's: the clock is in the module's
    // zone, not the JVM's (which is UTC in our containers).
    private void requireNoteDate(LocalDate noteDate) {
        LocalDate today = LocalDate.now(clock);
        if (noteDate.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new InvalidRequestException(
                    "A site note is dated no more than " + MAX_DAYS_AHEAD + " day ahead; " + noteDate + " is too far out");
        }
        if (noteDate.isBefore(today.minusDays(MAX_DAYS_BEHIND))) {
            throw new InvalidRequestException(
                    "A site note is dated no more than " + MAX_DAYS_BEHIND + " days behind; " + noteDate
                            + " is too far back to record as a contemporaneous note");
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

    // A site note is a record of what its author did or saw; anyone with a manage role could
    // otherwise rewrite anyone else's note, including its date. A system admin may still fix one.
    private void requireAuthorOrAdmin(SiteNote note) {
        Employee caller = currentEmployeeService.requireCurrentEmployee("change a site note");
        if (caller.getId().equals(note.getAuthorEmployeeId())
                || orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")) {
            return;
        }
        throw new AccessDeniedException("Only the author or a system admin may change this site note");
    }

    private void requirePhotoRoom(UUID id, int incoming) {
        if (incoming == 0) {
            return;
        }
        if (incoming > MAX_PHOTOS) {
            throw new InvalidRequestException(
                    "A site note carries at most " + MAX_PHOTOS + " photo; " + incoming + " were declared");
        }
        if (!attachmentService.getAttachments(SiteNotePhotos.ENTITY_TYPE, id).isEmpty()) {
            throw new InvalidRequestException(
                    "This site note already has a photo; remove it before adding another");
        }
    }

    private static void requireImageUploads(List<UploadRequest> uploads) {
        for (UploadRequest upload : uploads) {
            requireImageContentType(upload == null ? null : upload.contentType());
        }
    }

    private static void requireImageRegistrations(List<RegisterUploadRequest> uploads) {
        for (RegisterUploadRequest upload : uploads) {
            requireImageContentType(upload == null ? null : upload.contentType());
        }
    }

    private static void requireImageContentType(String contentType) {
        if (contentType == null || !contentType.startsWith(IMAGE_CONTENT_TYPE_PREFIX)) {
            throw new InvalidRequestException(
                    "A site note photo must be an image; got " + (contentType == null ? "no declaration" : contentType));
        }
    }
}

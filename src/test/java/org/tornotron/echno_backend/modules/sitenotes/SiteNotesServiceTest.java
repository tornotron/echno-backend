package org.tornotron.echno_backend.modules.sitenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.sitenotes.domain.SiteNote;
import org.tornotron.echno_backend.modules.sitenotes.domain.SiteNotePhotos;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNoteDto;
import org.tornotron.echno_backend.modules.sitenotes.dto.UpdateSiteNoteRequest;
import org.tornotron.echno_backend.modules.sitenotes.mapper.SiteNotesMapper;
import org.tornotron.echno_backend.modules.sitenotes.repository.SiteNoteRepository;
import org.tornotron.echno_backend.modules.sitenotes.service.SiteNotesService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Rules of {@link SiteNotesService} that do not need a real database: a storage key is bound to
 * the note it was presigned for (the same rule {@code ObservationEvidenceServiceTest} proves for
 * observation evidence), a note carries at most one photo and it must be an image, and only the
 * author or a system admin may change a note afterward.
 */
class SiteNotesServiceTest {

    private static final Long ORG_ID = 100L;
    private static final Long AUTHOR_ID = 7L;
    private static final Long OTHER_MANAGER_ID = 9L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private final SiteNoteRepository notes = mock(SiteNoteRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
    private final TenantEntityHelper tenantEntityHelper = mock(TenantEntityHelper.class);
    private final UserContextService userContextService = mock(UserContextService.class);
    private final CurrentEmployeeService currentEmployeeService = mock(CurrentEmployeeService.class);
    private final OrganizationSecurityService orgSecurity = mock(OrganizationSecurityService.class);
    private final SiteNotesMapper mapper = mock(SiteNotesMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private SiteNotesService service;
    private final UUID noteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SiteNotesService(notes, projects, employees, attachmentService, attachmentMapper,
                tenantEntityHelper, userContextService, currentEmployeeService, orgSecurity, mapper, events, CLOCK);
        when(mapper.toDto(any())).thenReturn(
                new SiteNoteDto(noteId, 1L, LocalDate.now(CLOCK), AUTHOR_ID, "note", null, null));
        when(notes.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    // ---------------------------------------------------------- photo count and type

    @Test
    void presign_refusesMoreThanOnePhoto() {
        SiteNote note = noteAuthoredBy(AUTHOR_ID);
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(note));
        List<UploadRequest> uploads = List.of(
                new UploadRequest("a.jpg", "image/jpeg", 10L), new UploadRequest("b.jpg", "image/jpeg", 10L));

        assertThatThrownBy(() -> service.presignPhotos(noteId, uploads)).isInstanceOf(InvalidRequestException.class);
        verify(attachmentService, never()).presignUploads(any(), any(), any());
    }

    @Test
    void presign_refusesASecondPhotoWhenOneAlreadyExists() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        when(attachmentService.getAttachments(eq(SiteNotePhotos.ENTITY_TYPE), eq(noteId)))
                .thenReturn(List.of(new AttachmentDto()));
        List<UploadRequest> uploads = List.of(new UploadRequest("a.jpg", "image/jpeg", 10L));

        assertThatThrownBy(() -> service.presignPhotos(noteId, uploads)).isInstanceOf(InvalidRequestException.class);
        verify(attachmentService, never()).presignUploads(any(), any(), any());
    }

    @Test
    void presign_refusesANonImageContentType() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        List<UploadRequest> uploads = List.of(new UploadRequest("a.pdf", "application/pdf", 10L));

        assertThatThrownBy(() -> service.presignPhotos(noteId, uploads)).isInstanceOf(InvalidRequestException.class);
        verify(attachmentService, never()).presignUploads(any(), any(), any());
    }

    @Test
    void presign_refusesANullDeclarationWithA400NotAnNpe() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        List<UploadRequest> uploads = Arrays.asList((UploadRequest) null);

        assertThatThrownBy(() -> service.presignPhotos(noteId, uploads)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void presign_issuesAKeyForOnePhoto() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        List<UploadRequest> uploads = List.of(new UploadRequest("a.jpg", "image/jpeg", 10L));

        service.presignPhotos(noteId, uploads);

        verify(attachmentService).presignUploads(eq(uploads), any(), eq("site/" + noteId));
    }

    // ---------------------------------------------------------- storage key ownership

    @Test
    void register_refusesAKeyPresignedForAnotherNote() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        UUID other = UUID.randomUUID();
        List<RegisterUploadRequest> uploads = List.of(
                new RegisterUploadRequest("site/" + other + "/a.jpg", "a.jpg", "image/jpeg", 10L));

        assertThatThrownBy(() -> service.registerPhotos(noteId, uploads))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not presigned for this note");
        verify(attachmentService, never()).registerUploads(any(), any(), any());
    }

    @Test
    void register_refusesAKeyOutsideTheNoteFolder() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        List<RegisterUploadRequest> uploads = List.of(
                new RegisterUploadRequest("inspection/a.jpg", "a.jpg", "image/jpeg", 10L));

        assertThatThrownBy(() -> service.registerPhotos(noteId, uploads)).isInstanceOf(InvalidRequestException.class);
        verify(attachmentService, never()).registerUploads(any(), any(), any());
    }

    @Test
    void register_acceptsAKeyUnderTheNoteFolder() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        List<RegisterUploadRequest> uploads = List.of(
                new RegisterUploadRequest("site/" + noteId + "/a.jpg", "a.jpg", "image/jpeg", 10L));
        when(attachmentService.registerUploads(eq(uploads), any(), eq("site/" + noteId))).thenReturn(List.of());

        service.registerPhotos(noteId, uploads);

        verify(attachmentService).registerUploads(eq(uploads), any(), eq("site/" + noteId));
    }

    // ---------------------------------------------------------- update restricted to author

    @Test
    void update_isAllowedForTheAuthor() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        when(currentEmployeeService.requireCurrentEmployee(any())).thenReturn(employee(AUTHOR_ID));

        service.update(noteId, new UpdateSiteNoteRequest(LocalDate.now(CLOCK), "Changed"));

        verify(notes).save(any());
    }

    @Test
    void update_isRefusedForAnotherManagerWhoIsNotTheAuthor() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        when(currentEmployeeService.requireCurrentEmployee(any())).thenReturn(employee(OTHER_MANAGER_ID));
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(false);

        assertThatThrownBy(() -> service.update(noteId, new UpdateSiteNoteRequest(LocalDate.now(CLOCK), "Changed")))
                .isInstanceOf(AccessDeniedException.class);
        verify(notes, never()).save(any());
    }

    @Test
    void update_isAllowedForASystemAdminWhoIsNotTheAuthor() {
        when(notes.findByIdScoped(noteId)).thenReturn(Optional.of(noteAuthoredBy(AUTHOR_ID)));
        when(currentEmployeeService.requireCurrentEmployee(any())).thenReturn(employee(OTHER_MANAGER_ID));
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin")).thenReturn(true);

        service.update(noteId, new UpdateSiteNoteRequest(LocalDate.now(CLOCK), "Changed"));

        verify(notes).save(any());
    }

    // ---------------------------------------------------------- helpers

    private static SiteNote noteAuthoredBy(Long authorId) {
        SiteNote note = new SiteNote();
        note.setOrganization(organization());
        note.setAuthorEmployeeId(authorId);
        note.setNoteDate(LocalDate.now(CLOCK));
        note.setNote("existing");
        return note;
    }

    private static Organization organization() {
        Organization org = new Organization();
        org.setId(ORG_ID);
        return org;
    }

    private static Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setStatus(EmployeeStatus.active);
        return employee;
    }
}

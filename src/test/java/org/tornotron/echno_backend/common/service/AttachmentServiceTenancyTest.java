package org.tornotron.echno_backend.common.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.dto.StoredFile;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.issue.IssueRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.TaskRepository;
import org.tornotron.echno_backend.user.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies uploaded attachments are stamped with the current tenant's organization.
 * Without it the row's organization_id stays null and the entity's orgFilter (and the
 * fail-closed load listener) skip the row, leaving it outside tenant scoping.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTenancyTest {

    @Mock private AttachmentRepository attachmentRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private IssueRepository issueRepository;
    @Mock private UserRepository userRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private org.tornotron.echno_backend.common.mapper.AttachmentMapper attachmentMapper;
    @Mock private MultipartFile file;

    private AttachmentService service;

    @BeforeEach
    void setUp() {
        service = new AttachmentService(attachmentRepository, fileStorageService, organizationRepository,
                projectRepository, taskRepository, issueRepository, userRepository, attendanceRepository,
                tenantEntityHelper, attachmentMapper);
    }

    @Test
    void uploadAttachment_stampsCurrentTenantOrganization() {
        Organization org = new Organization();
        org.setId(7L);

        when(file.getOriginalFilename()).thenReturn("photo.jpg");
        when(file.getSize()).thenReturn(10L);
        when(attachmentRepository.existsByEntityTypeAndEntityIdAndOriginalFilenameAndFileSize(
                "ISSUE", 1L, "photo.jpg", 10L)).thenReturn(false);
        when(fileStorageService.uploadFile(eq(file), any())).thenReturn(new StoredFile("k", "image/jpeg", 10L));
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(org);
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Folder that hits linkToEntity's default branch, so no parent lookup is needed.
        Attachment result = service.uploadAttachment(file, "ISSUE", 1L, "misc");

        assertThat(result.getOrganization()).isSameAs(org);
    }

    /**
     * The scoped delete, which the inspection evidence path uses. {@code deleteAttachment(Long)}
     * resolves by numeric id alone, so it lets a caller reach another organization's file and lets
     * a caller who may edit one record delete a file belonging to a different one. This one has to
     * agree on the organization, the entity type and the key before it removes anything.
     */
    @Nested
    class ScopedDelete {

        private final UUID inspectionId = UUID.randomUUID();

        @BeforeEach
        void actAsOrgSeven() {
            TenantContext.setCurrentOrgId(7L);
        }

        @AfterEach
        void stopActing() {
            TenantContext.clear();
        }

        private Attachment evidenceOf(UUID owningInspection) {
            Attachment attachment = new Attachment();
            attachment.setId(88L);
            attachment.setEntityType("INSPECTION_EVIDENCE");
            attachment.setEntityUuid(owningInspection);
            attachment.setStorageKey("inspection/fire-noc.pdf");
            return attachment;
        }

        @Test
        void removesTheFileAndItsStoredObjectWhenEverythingAgrees() {
            Attachment attachment = evidenceOf(inspectionId);
            when(attachmentRepository.findByIdAndOrganization_Id(88L, 7L))
                    .thenReturn(Optional.of(attachment));

            service.deleteAttachmentOf(AttachmentOwner.of("INSPECTION_EVIDENCE", inspectionId), 88L);

            verify(fileStorageService).deleteFile("inspection/fire-noc.pdf");
            verify(attachmentRepository).delete(attachment);
        }

        @Test
        void refusesAFileFiledAgainstADifferentRecord() {
            when(attachmentRepository.findByIdAndOrganization_Id(88L, 7L))
                    .thenReturn(Optional.of(evidenceOf(UUID.randomUUID())));

            assertThatThrownBy(() -> service.deleteAttachmentOf(
                    AttachmentOwner.of("INSPECTION_EVIDENCE", inspectionId), 88L))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(attachmentRepository, never()).delete(any(Attachment.class));
            verify(fileStorageService, never()).deleteFile(anyString());
        }

        @Test
        void refusesAFileFiledUnderADifferentEntityType() {
            when(attachmentRepository.findByIdAndOrganization_Id(88L, 7L))
                    .thenReturn(Optional.of(evidenceOf(inspectionId)));

            assertThatThrownBy(() -> service.deleteAttachmentOf(
                    AttachmentOwner.of("ASSET_DOCUMENTS", inspectionId), 88L))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(attachmentRepository, never()).delete(any(Attachment.class));
        }

        @Test
        void refusesAFileOfAnotherOrganization() {
            when(attachmentRepository.findByIdAndOrganization_Id(88L, 7L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteAttachmentOf(
                    AttachmentOwner.of("INSPECTION_EVIDENCE", inspectionId), 88L))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(fileStorageService, never()).deleteFile(anyString());
        }
    }
}

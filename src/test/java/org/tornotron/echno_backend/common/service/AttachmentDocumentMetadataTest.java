package org.tornotron.echno_backend.common.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.common.dto.AttachmentDocumentMetadataDto;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.issue.IssueRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.TaskRepository;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the document half of the asset ticket, which lands on the shared attachment
 * table rather than on a table of its own.
 *
 * <p>Two things are worth pinning. The expiry flags are derived on read, so a policy cannot sit
 * in the database marked valid the morning after it lapsed. And the metadata patch resolves the
 * attachment through the tenant-scoped finder, so a member of one organization cannot annotate
 * another's file by guessing a numeric id.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentDocumentMetadataTest {

    private static final Long ORG = 100L;
    private static final Long ATTACHMENT = 88L;

    @Mock private AttachmentRepository attachmentRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private IssueRepository issueRepository;
    @Mock private UserRepository userRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private AttachmentMapper attachmentMapper;

    private AttachmentService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new AttachmentService(attachmentRepository, fileStorageService, organizationRepository,
                projectRepository, taskRepository, issueRepository, userRepository, attendanceRepository,
                tenantEntityHelper, attachmentMapper);
        lenient().when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(attachmentMapper.toDto(any(Attachment.class))).thenAnswer(inv -> {
            Attachment attachment = inv.getArgument(0);
            AttachmentDto dto = new AttachmentDto();
            dto.setId(attachment.getId());
            dto.setDocumentType(attachment.getDocumentType());
            dto.setIssuedOn(attachment.getIssuedOn());
            dto.setExpiresOn(attachment.getExpiresOn());
            AttachmentMapper.fillExpiry(attachment.getExpiresOn(), dto);
            return dto;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AttachmentDocumentMetadataDto metadata(String type, LocalDate expiresOn) {
        AttachmentDocumentMetadataDto dto = new AttachmentDocumentMetadataDto();
        dto.setDocumentType(type);
        dto.setExpiresOn(expiresOn);
        return dto;
    }

    @Test
    void metadataIsRecordedAgainstTheAttachment() {
        Attachment attachment = new Attachment();
        attachment.setId(ATTACHMENT);
        when(attachmentRepository.findByIdAndOrganization_Id(ATTACHMENT, ORG))
                .thenReturn(Optional.of(attachment));

        LocalDate expiry = LocalDate.now().plusDays(285);

        AttachmentDto dto = service.updateDocumentMetadata(ATTACHMENT, metadata("insurance", expiry));

        assertThat(attachment.getDocumentType()).isEqualTo("insurance");
        assertThat(dto.getExpiresOn()).isEqualTo(expiry);
        verify(attachmentRepository).save(attachment);
        // Resolved through the tenant-scoped finder, not the unscoped findById.
        verify(attachmentRepository).findByIdAndOrganization_Id(ATTACHMENT, ORG);
    }

    @Test
    void anAttachmentOfAnotherOrganizationIsNotFound() {
        when(attachmentRepository.findByIdAndOrganization_Id(ATTACHMENT, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.updateDocumentMetadata(ATTACHMENT, metadata("insurance", null)))
                .withMessageContaining("was not found in this organization");
        verify(attachmentRepository, never()).save(any(Attachment.class));
    }

    @Test
    void aDocumentThatHasLapsedReadsAsExpired() {
        AttachmentDto dto = new AttachmentDto();
        AttachmentMapper.fillExpiry(LocalDate.now().minusDays(7), dto);

        assertThat(dto.getExpired()).isTrue();
        assertThat(dto.getDaysUntilExpiry()).isEqualTo(-7L);
    }

    @Test
    void aDocumentExpiringTodayHasNotLapsedYet() {
        AttachmentDto dto = new AttachmentDto();
        AttachmentMapper.fillExpiry(LocalDate.now(), dto);

        assertThat(dto.getExpired()).isFalse();
        assertThat(dto.getDaysUntilExpiry()).isZero();
    }

    @Test
    void aFileWithNoExpiryCarriesNoExpiryFlagsRatherThanFalseOnes() {
        AttachmentDto dto = new AttachmentDto();
        AttachmentMapper.fillExpiry(null, dto);

        assertThat(dto.getExpired()).isNull();
        assertThat(dto.getDaysUntilExpiry()).isNull();
    }
}

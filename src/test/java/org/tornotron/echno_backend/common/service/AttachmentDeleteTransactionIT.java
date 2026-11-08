package org.tornotron.echno_backend.common.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.issue.IssueRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.task.TaskRepository;
import org.tornotron.echno_backend.user.UserRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Deleting an attachment against a real transaction (#901). A stored file cannot be restored by a
 * rollback, so it may only go once the row's delete has actually committed. The test itself runs
 * outside a transaction so the commit and the rollback are real ones.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TenantIsolationListenerRegistrar.class, UnscopedAccessGuard.class, SimpleMeterRegistry.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttachmentDeleteTransactionIT extends AbstractIntegrationTest {

    private static final String ENTITY_TYPE = "INSPECTION_EVIDENCE";
    private static final String KEY = "inspection/slab-crack.jpg";

    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private final FileStorageService fileStorageService = Mockito.mock(FileStorageService.class);
    private final UUID owner = UUID.randomUUID();
    private AttachmentService service;
    private TransactionTemplate transaction;
    private Long orgId;
    private Long attachmentId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        service = new AttachmentService(attachmentRepository, fileStorageService,
                Mockito.mock(ProjectRepository.class), Mockito.mock(TaskRepository.class),
                Mockito.mock(IssueRepository.class), Mockito.mock(UserRepository.class),
                Mockito.mock(AttendanceRepository.class), Mockito.mock(TenantEntityHelper.class),
                Mockito.mock(AttachmentMapper.class));
        transaction = new TransactionTemplate(transactionManager);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = new Organization();
        org.setOrganizationName("Attachment Org " + suffix);
        org.setOrganizationAddress("Attachment Org address");
        org.setOrganizationEmail("attachment-" + suffix + "@example.test");
        org.setOrganizationPhone("0000000000");
        org = organizationRepository.saveAndFlush(org);
        orgId = org.getId();
        TenantContext.setCurrentOrgId(orgId);

        Organization owningOrg = org;
        attachmentId = transaction.execute(status -> {
            Attachment attachment = new Attachment();
            attachment.setEntityType(ENTITY_TYPE);
            attachment.setEntityUuid(owner);
            attachment.setStorageKey(KEY);
            attachment.setOrganization(owningOrg);
            attachment.setCreatedAt(LocalDateTime.of(2026, 9, 1, 9, 0));
            attachment.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 9, 0));
            return attachmentRepository.saveAndFlush(attachment).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(status -> {
            attachmentRepository.findByIdAndOrganization_Id(attachmentId, orgId)
                    .ifPresent(attachmentRepository::delete);
        });
        TenantContext.clear();
        transaction.executeWithoutResult(status -> organizationRepository.deleteById(orgId));
    }

    @Test
    void aCallerThatFailsAfterTheDeleteKeepsBothTheRowAndTheFile() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.deleteAttachmentOf(AttachmentOwner.of(ENTITY_TYPE, owner), attachmentId);
            throw new IllegalStateException("a later statement failed");
        })).isInstanceOf(IllegalStateException.class);

        verify(fileStorageService, never()).deleteFile(anyString());
        assertThat(rowExists()).isTrue();
    }

    @Test
    void theByIdDeleteAlsoLeavesTheFileWhenTheCallerRollsBack() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.deleteAttachment(attachmentId);
            throw new IllegalStateException("a later statement failed");
        })).isInstanceOf(IllegalStateException.class);

        verify(fileStorageService, never()).deleteFile(anyString());
        assertThat(rowExists()).isTrue();
    }

    @Test
    void theFileGoesOnlyOnceTheDeleteHasCommitted() {
        transaction.executeWithoutResult(status -> {
            service.deleteAttachmentOf(AttachmentOwner.of(ENTITY_TYPE, owner), attachmentId);
            verify(fileStorageService, never()).deleteFile(anyString());
        });

        verify(fileStorageService).deleteFile(KEY);
        assertThat(rowExists()).isFalse();
    }

    @Test
    void aFailedFileDeleteAfterTheCommitLeavesAnOrphanAndDoesNotFailTheCaller() {
        doThrow(new IllegalStateException("storage away")).when(fileStorageService).deleteFile(KEY);

        transaction.executeWithoutResult(status ->
                service.deleteAttachmentOf(AttachmentOwner.of(ENTITY_TYPE, owner), attachmentId));

        verify(fileStorageService).deleteFile(KEY);
        assertThat(rowExists()).isFalse();
    }

    private boolean rowExists() {
        return Boolean.TRUE.equals(transaction.execute(status ->
                attachmentRepository.findByIdAndOrganization_Id(attachmentId, orgId).isPresent()));
    }
}

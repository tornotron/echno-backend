package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationIntakeUpload;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeUploadPresignRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeUploadPresignResponse;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeUploadRegisterRequest;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationIntakeUploadRepository;
import org.tornotron.echno_backend.modules.inspections.service.ObservationIntakeUploadService;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The producer upload path: what it accepts, the folder a key is bound to, the project and the
 * principal it records, and that one producer cannot register into another's upload.
 */
@ExtendWith(MockitoExtension.class)
class ObservationIntakeUploadServiceTest {

    private static final Long ORG = 7L;
    private static final Long PROJECT = 42L;

    @Mock private ObservationIntakeUploadRepository uploads;
    @Mock private AttachmentService attachmentService;
    @Mock private AttachmentMapper attachmentMapper;
    @Mock private ProjectRepository projectRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private UserContextService userContextService;

    @InjectMocks
    private ObservationIntakeUploadService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        lenient().when(userContextService.getCurrentKeycloakId()).thenReturn("drone-fleet");
        lenient().when(projectRepository.existsByIdAndOrganization_Id(PROJECT, ORG)).thenReturn(true);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void presignRecordsTheUploadAndIssuesKeysIntoItsOwnFolder() {
        List<UploadRequest> files = List.of(jpeg("a.jpg"));

        IntakeUploadPresignResponse response = service.presign(new IntakeUploadPresignRequest(PROJECT, files));

        ArgumentCaptor<ObservationIntakeUpload> saved = ArgumentCaptor.forClass(ObservationIntakeUpload.class);
        verify(uploads).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(response.uploadId());
        assertThat(saved.getValue().getProjectId()).isEqualTo(PROJECT);
        assertThat(saved.getValue().getProducerSubject()).isEqualTo("drone-fleet");
        AttachmentOwner owner = AttachmentOwner.of("OBSERVATION_INTAKE_UPLOAD", response.uploadId());
        verify(attachmentService).presignUploads(files, owner,
                "observations/intake/" + ORG + "/" + response.uploadId());
    }

    @Test
    void presignRefusesAProjectOutsideTheOrganization() {
        when(projectRepository.existsByIdAndOrganization_Id(99L, ORG)).thenReturn(false);

        assertThatThrownBy(() -> service.presign(new IntakeUploadPresignRequest(99L, List.of(jpeg("a.jpg")))))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(uploads, never()).save(any());
    }

    @Test
    void presignRefusesAnythingButASmallEnoughImage() {
        assertRefused(new UploadRequest("a.pdf", "application/pdf", 10L), "image/jpeg");
        assertRefused(new UploadRequest("a.jpg", null, 10L), "image/jpeg");
        assertRefused(new UploadRequest("a.jpg", "image/jpeg", null), "size");
        assertRefused(new UploadRequest("a.jpg", "image/jpeg", 25L * 1024 * 1024 + 1), "25 MB");
    }

    @Test
    void presignAcceptsEachImageTypeAtTheLimit() {
        service.presign(new IntakeUploadPresignRequest(PROJECT, List.of(
                new UploadRequest("a.jpg", "image/jpeg", 25L * 1024 * 1024),
                new UploadRequest("b.png", "IMAGE/PNG", 1L),
                new UploadRequest("c.webp", "image/webp", 1L))));

        verify(attachmentService).presignUploads(any(), any(), any());
    }

    @Test
    void presignRefusesMoreThanTwentyFiles() {
        assertThatThrownBy(() -> service.presign(new IntakeUploadPresignRequest(PROJECT,
                Collections.nCopies(21, jpeg("a.jpg")))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("20");
    }

    @Test
    void registerFilesTheImagesAgainstTheUploadAndItsProject() {
        UUID id = UUID.randomUUID();
        when(uploads.findByIdScoped(id)).thenReturn(Optional.of(upload(id, "drone-fleet")));
        Project project = mock(Project.class);
        when(projectRepository.getReferenceById(PROJECT)).thenReturn(project);
        Attachment stored = new Attachment();
        String folder = "observations/intake/" + ORG + "/" + id;
        List<RegisterUploadRequest> files = List.of(registered(folder + "/x-a.jpg"));
        when(attachmentService.registerUploads(eq(files), any(), eq(folder))).thenReturn(List.of(stored));

        service.register(new IntakeUploadRegisterRequest(id, files));

        assertThat(stored.getProject()).isSameAs(project);
        verify(attachmentMapper).toDto(stored);
    }

    @Test
    void registerRefusesAKeyPresignedForAnotherUpload() {
        UUID id = UUID.randomUUID();
        when(uploads.findByIdScoped(id)).thenReturn(Optional.of(upload(id, "drone-fleet")));
        String other = "observations/intake/" + ORG + "/" + UUID.randomUUID() + "/x-a.jpg";

        assertThatThrownBy(() -> service.register(new IntakeUploadRegisterRequest(id, List.of(registered(other)))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not presigned");
        verify(attachmentService, never()).registerUploads(any(), any(AttachmentOwner.class), any());
    }

    @Test
    void anotherProducersUploadReadsAsAbsent() {
        UUID id = UUID.randomUUID();
        when(uploads.findByIdScoped(id)).thenReturn(Optional.of(upload(id, "another-fleet")));

        assertThatThrownBy(() -> service.register(new IntakeUploadRegisterRequest(id, List.of(registered("k")))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void assertRefused(UploadRequest file, String message) {
        assertThatThrownBy(() -> service.presign(new IntakeUploadPresignRequest(PROJECT, List.of(file))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining(message);
        verify(uploads, never()).save(any());
    }

    private static UploadRequest jpeg(String name) {
        return new UploadRequest(name, "image/jpeg", 1024L);
    }

    private static RegisterUploadRequest registered(String key) {
        return new RegisterUploadRequest(key, "a.jpg", "image/jpeg", 1024L);
    }

    private static ObservationIntakeUpload upload(UUID id, String subject) {
        ObservationIntakeUpload upload = new ObservationIntakeUpload();
        upload.setId(id);
        upload.setProjectId(PROJECT);
        upload.setProducerSubject(subject);
        return upload;
    }
}

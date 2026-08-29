package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.inspection.service.InspectionEvidenceService;

import java.util.List;
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
 * The rules that make an inspection's evidence usable as an audit record: it is filed against the
 * inspection's own UUID, it may be added at any point in the inspection's life, and it may not be
 * taken away once the inspection has been judged.
 *
 * <p>Plain Mockito with no Spring context, deliberately: the test JVM caches a context per
 * distinct test configuration and is capped at 1 GB with no fork between classes, so a new
 * {@code @SpringBootTest} here would be paid for by an unrelated test failing on heap.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InspectionEvidenceServiceTest {

    @Mock private InspectionRepository inspectionRepo;
    @Mock private AttachmentService attachmentService;
    @Mock private AttachmentMapper attachmentMapper;

    private InspectionEvidenceService service;
    private UUID inspectionId;

    @BeforeEach
    void setUp() {
        service = new InspectionEvidenceService(inspectionRepo, attachmentService, attachmentMapper);
        inspectionId = UUID.randomUUID();
    }

    private Inspection inspectionWith(InspectionStatus status) {
        Inspection inspection = new Inspection();
        inspection.setId(inspectionId);
        inspection.setInspectionNumber("INSP-0007");
        inspection.setStatus(status);
        when(inspectionRepo.findByIdScoped(inspectionId)).thenReturn(Optional.of(inspection));
        return inspection;
    }

    private static MultipartFile aFile() {
        return new MockMultipartFile("attachments", "fire-noc.pdf", "application/pdf", new byte[]{1});
    }

    @Test
    void evidenceIsFiledAgainstTheInspectionsUuid() {
        inspectionWith(InspectionStatus.IN_PROGRESS);
        when(attachmentService.uploadAttachments(any(), any(AttachmentOwner.class), anyString()))
                .thenReturn(List.of(new Attachment()));
        when(attachmentMapper.toDto(any())).thenReturn(new AttachmentDto());

        service.upload(inspectionId, List.of(aFile()));

        ArgumentCaptor<AttachmentOwner> owner = ArgumentCaptor.forClass(AttachmentOwner.class);
        verify(attachmentService).uploadAttachments(any(), owner.capture(), eq("inspection"));
        assertThat(owner.getValue().entityUuid()).isEqualTo(inspectionId);
        assertThat(owner.getValue().entityId()).isNull();
        assertThat(owner.getValue().entityType()).isEqualTo("INSPECTION_EVIDENCE");
    }

    @Test
    void evidenceCanStillBeAddedOnceTheInspectionHasPassed() {
        inspectionWith(InspectionStatus.PASSED);
        when(attachmentService.uploadAttachments(any(), any(AttachmentOwner.class), anyString()))
                .thenReturn(List.of(new Attachment()));
        when(attachmentMapper.toDto(any())).thenReturn(new AttachmentDto());

        // The certificate a compliance inspection stands in for routinely arrives after the work
        // was signed off. Freezing additions at the verdict would make the record unfinishable.
        assertThat(service.upload(inspectionId, List.of(aFile()))).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = InspectionStatus.class,
            names = {"PASSED", "PASSED_WITH_REMARKS", "FAILED"})
    void evidenceCannotBeRemovedOnceTheInspectionHasBeenJudged(InspectionStatus judged) {
        inspectionWith(judged);

        assertThatThrownBy(() -> service.delete(inspectionId, 88L))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("INSP-0007")
                .hasMessageContaining("cannot be removed");

        verify(attachmentService, never()).deleteAttachmentOf(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = InspectionStatus.class,
            names = {"SUGGESTED", "SCHEDULED", "IN_PROGRESS", "COMPLETED", "CANCELLED"})
    void evidenceCanBeRemovedBeforeTheInspectionIsJudged(InspectionStatus unjudged) {
        inspectionWith(unjudged);

        service.delete(inspectionId, 88L);

        ArgumentCaptor<AttachmentOwner> owner = ArgumentCaptor.forClass(AttachmentOwner.class);
        verify(attachmentService).deleteAttachmentOf(owner.capture(), eq(88L));
        assertThat(owner.getValue().entityUuid()).isEqualTo(inspectionId);
    }

    @Test
    void anInspectionOfAnotherTenantHasNoEvidenceToRead() {
        when(inspectionRepo.findByIdScoped(inspectionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(inspectionId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(attachmentService, never()).getAttachments(anyString(), any(UUID.class));
    }

    @Test
    void evidenceCannotBeAttachedToAnInspectionOfAnotherTenant() {
        when(inspectionRepo.findByIdScoped(inspectionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(inspectionId, List.of(aFile())))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(attachmentService, never()).uploadAttachments(any(), any(AttachmentOwner.class), anyString());
    }

    @Test
    void readingEvidenceAsksForTheInspectionsOwnFiles() {
        inspectionWith(InspectionStatus.COMPLETED);
        when(attachmentService.getAttachments("INSPECTION_EVIDENCE", inspectionId))
                .thenReturn(List.of(new AttachmentDto()));

        assertThat(service.list(inspectionId)).hasSize(1);
        verify(attachmentService).getAttachments("INSPECTION_EVIDENCE", inspectionId);
    }

    @Test
    void presigningEvidenceGoesToTheInspectionFolderUnderTheInspectionsUuid() {
        inspectionWith(InspectionStatus.SCHEDULED);

        service.presign(inspectionId, List.of());

        ArgumentCaptor<AttachmentOwner> owner = ArgumentCaptor.forClass(AttachmentOwner.class);
        verify(attachmentService).presignUploads(any(), owner.capture(), eq("inspection"));
        assertThat(owner.getValue().entityUuid()).isEqualTo(inspectionId);
    }
}

package org.tornotron.echno_backend.modules.inspections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;
import org.tornotron.echno_backend.common.dto.UploadRequest;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.modules.inspections.service.ObservationEvidenceService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A storage key is bound to the observation it was presigned for: uploads are issued
 * into {@code observation/<id>} and registration refuses a key from anywhere else.
 */
@ExtendWith(MockitoExtension.class)
class ObservationEvidenceServiceTest {

    @Mock
    private ObservationRepository observationRepo;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private AttachmentMapper attachmentMapper;
    @Mock
    private InspectionEventRecorder events;

    @InjectMocks
    private ObservationEvidenceService service;

    private final UUID observationId = UUID.randomUUID();

    @Test
    void presign_issuesKeysIntoTheObservationsOwnFolder() {
        when(observationRepo.findByIdScoped(observationId)).thenReturn(Optional.of(new Observation()));
        List<UploadRequest> uploads = List.of(new UploadRequest("crack.jpg", "image/jpeg", 10L));

        service.presign(observationId, uploads);

        verify(attachmentService).presignUploads(eq(uploads), any(), eq("observation/" + observationId));
    }

    @Test
    void register_refusesAKeyPresignedForAnotherObservation() {
        when(observationRepo.findByIdScoped(observationId)).thenReturn(Optional.of(new Observation()));
        UUID other = UUID.randomUUID();
        List<RegisterUploadRequest> uploads = List.of(
                new RegisterUploadRequest("observation/" + other + "/abc_crack.jpg", "crack.jpg", "image/jpeg", 10L));

        assertThatThrownBy(() -> service.register(observationId, uploads))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not presigned for this observation");
        verify(attachmentService, never()).registerUploads(any(), any(), any());
    }

    @Test
    void register_refusesAKeyOutsideTheObservationFolder() {
        when(observationRepo.findByIdScoped(observationId)).thenReturn(Optional.of(new Observation()));
        List<RegisterUploadRequest> uploads = List.of(
                new RegisterUploadRequest("inspection/abc_crack.jpg", "crack.jpg", "image/jpeg", 10L));

        assertThatThrownBy(() -> service.register(observationId, uploads))
                .isInstanceOf(InvalidRequestException.class);
        verify(attachmentService, never()).registerUploads(any(), any(), any());
    }

    @Test
    void register_acceptsAKeyUnderTheObservationFolder() {
        when(observationRepo.findByIdScoped(observationId)).thenReturn(Optional.of(new Observation()));
        List<RegisterUploadRequest> uploads = List.of(
                new RegisterUploadRequest("observation/" + observationId + "/abc_crack.jpg", "crack.jpg", "image/jpeg", 10L));
        when(attachmentService.registerUploads(eq(uploads), any(), eq("observation/" + observationId)))
                .thenReturn(List.of());

        service.register(observationId, uploads);

        verify(attachmentService).registerUploads(eq(uploads), any(), eq("observation/" + observationId));
    }
}

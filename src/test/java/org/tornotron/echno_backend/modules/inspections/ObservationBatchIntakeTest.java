package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.inspections.api.IntakeActor;
import org.tornotron.echno_backend.modules.inspections.api.IntakeRejectedException;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeBatchResponse;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.observation.IntakeReferenceNotFoundException;
import org.tornotron.echno_backend.modules.inspections.service.ObservationBatchIntakeService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationIntakeUploadService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.modules.inspections.web.InspectionsExceptionHandler;
import org.tornotron.echno_backend.modules.inspections.web.IntakeBatchBodyLimit;
import org.tornotron.echno_backend.modules.inspections.web.ObservationIntakeController;

/**
 * What a batch answers: every outcome and every code on the right item, and the limits that refuse a
 * whole call before anything is written. {@link ObservationIntakeIT} proves the transactions.
 */
class ObservationBatchIntakeTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final ObservationService observations = mock(ObservationService.class);
    private final ObservationBatchIntakeService service = new ObservationBatchIntakeService(observations, VALIDATOR);

    @Test
    void eachItemIsAnsweredInOrderWithItsCode() {
        when(observations.intake(argThat(ref("new")), isNull())).thenAnswer(call -> result(true));
        when(observations.intake(argThat(ref("old")), isNull())).thenAnswer(call -> result(false));
        when(observations.intake(argThat(ref("unknown-class")), isNull())).thenThrow(
                new IntakeRejectedException("UNKNOWN_CLASS", "regions[0].classKey", "No such class"));
        when(observations.intake(argThat(ref("no-project")), isNull())).thenThrow(
                new IntakeReferenceNotFoundException("PROJECT_NOT_FOUND", "No such project"));
        when(observations.intake(argThat(ref("no-node")), isNull())).thenThrow(
                new ResourceNotFoundException("No such node"));
        when(observations.intake(argThat(ref("boom")), isNull())).thenThrow(new IllegalStateException("db host secret"));

        IntakeBatchResponse response = service.intakeAll(List.of(item("new"), item("old"), item("unknown-class"),
                item("no-project"), item("no-node"), item("boom")), null);

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.existing()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(4);
        assertThat(response.results()).extracting(IntakeBatchResponse.Item::index).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(response.results()).extracting(IntakeBatchResponse.Item::status).containsExactly(
                IntakeBatchResponse.Status.CREATED, IntakeBatchResponse.Status.EXISTING,
                IntakeBatchResponse.Status.REJECTED, IntakeBatchResponse.Status.REJECTED,
                IntakeBatchResponse.Status.REJECTED, IntakeBatchResponse.Status.REJECTED);
        assertThat(response.results().get(0).observationId()).isNotNull();
        assertThat(response.results().get(2).error().code()).isEqualTo("UNKNOWN_CLASS");
        assertThat(response.results().get(2).error().field()).isEqualTo("regions[0].classKey");
        assertThat(response.results().get(3).error().code()).isEqualTo("PROJECT_NOT_FOUND");
        assertThat(response.results().get(4).error().code()).isEqualTo("VALIDATION");
        assertThat(response.results().get(5).error().code()).isEqualTo("INTERNAL");
        assertThat(response.results().get(5).error().message()).doesNotContain("secret");
    }

    @Test
    void anItemThatFailsItsConstraintsIsRefusedWithoutReachingTheService() {
        IntakeObservationRequest noTitle = new IntakeObservationRequest(1L, "a", ObservationSource.AI, "d", null, null, null,
                null, LocalDateTime.of(2026, 11, 8, 9, 0), " ", null, null, null, null, null, null, null, null);

        IntakeBatchResponse response = service.intakeAll(Arrays.asList(noTitle, null), null);

        assertThat(response.rejected()).isEqualTo(2);
        assertThat(response.results().get(0).error().code()).isEqualTo("VALIDATION");
        assertThat(response.results().get(0).error().field()).isEqualTo("title");
        assertThat(response.results().get(1).error().code()).isEqualTo("VALIDATION");
        verifyNoInteractions(observations);
    }

    @Test
    void theSecondUseOfAReferenceInOneBatchIsRefused() {
        when(observations.intake(any(), isNull())).thenAnswer(call -> result(true));

        IntakeBatchResponse response = service.intakeAll(List.of(item("twice"), item("twice")), null);

        assertThat(response.results().get(0).status()).isEqualTo(IntakeBatchResponse.Status.CREATED);
        assertThat(response.results().get(1).error().code()).isEqualTo("DUPLICATE_IN_BATCH");
        verify(observations, times(1)).intake(any(), isNull());
    }

    @Test
    void theActorIsPassedThrough() {
        IntakeActor actor = IntakeActor.ai("ppe");
        when(observations.intake(any(), any(IntakeActor.class))).thenAnswer(call -> result(true));

        service.intakeAll(List.of(item("a")), actor);

        verify(observations).intake(any(), argThat(actor::equals));
    }

    @Test
    void anEmptyOrOverlongEnvelopeIsRefusedWhole() throws Exception {
        ObservationBatchIntakeService batch = mock(ObservationBatchIntakeService.class);
        MockMvc mockMvc = web(batch);

        mockMvc.perform(post("/api/v1/observations/intake/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add("{}");
        }
        mockMvc.perform(post("/api/v1/observations/intake/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + String.join(",", many) + "]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(batch);
    }

    @Test
    void aBodyOverTwoMegabytesIsRefusedBeforeItIsParsed() throws Exception {
        ObservationBatchIntakeService batch = mock(ObservationBatchIntakeService.class);

        web(batch).perform(post("/api/v1/observations/intake/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[\"" + "x".repeat(2 * 1024 * 1024) + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value("A batch body is limited to 2 MB"));
        verifyNoInteractions(batch);
    }

    private static MockMvc web(ObservationBatchIntakeService batch) {
        ObservationIntakeController controller = new ObservationIntakeController(mock(ObservationService.class),
                mock(ObservationIntakeUploadService.class), batch);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new IntakeBatchBodyLimit(), new InspectionsExceptionHandler())
                .setValidator(new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean() {{
                    afterPropertiesSet();
                }})
                .build();
    }

    private static org.mockito.ArgumentMatcher<IntakeObservationRequest> ref(String ref) {
        return request -> request != null && ref.equals(request.externalRef());
    }

    private static ObservationService.IntakeResult result(boolean created) {
        ObservationDto dto = mock(ObservationDto.class);
        when(dto.id()).thenReturn(UUID.randomUUID());
        return new ObservationService.IntakeResult(dto, created);
    }

    private static IntakeObservationRequest item(String ref) {
        return new IntakeObservationRequest(1L, ref, ObservationSource.DRONE, "drone-1", null, null, null, null,
                LocalDateTime.of(2026, 11, 8, 9, 0), "Seen", null, null, null, null, null, null, null, null);
    }
}

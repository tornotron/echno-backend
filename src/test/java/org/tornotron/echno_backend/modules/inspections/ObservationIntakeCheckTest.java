package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.inspections.api.IntakeCandidate;
import org.tornotron.echno_backend.modules.inspections.api.IntakeRejectedException;
import org.tornotron.echno_backend.modules.inspections.api.ObservationIntakeCheck;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationRegionRequest;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.mapper.ObservationMapper;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRegionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationIntakeUploadRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.modules.inspections.service.ObservationBatchIntakeService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationIntakeUploadService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.modules.inspections.web.InspectionsExceptionHandler;
import org.tornotron.echno_backend.modules.inspections.web.ObservationIntakeController;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The extension point another module uses to refuse a machine finding: every check bean sees a new
 * finding before anything is written, a refusal reaches the producer with its code, none need be
 * installed, and a repeat of an external reference is not checked again.
 */
@ExtendWith(MockitoExtension.class)
class ObservationIntakeCheckTest {

    private static final Long ORG = 7L;
    private static final Long PROJECT = 42L;
    private static final Long IMAGE = 900L;

    @Mock private ObservationRepository observationRepo;
    @Mock private InspectionRepository inspectionRepo;
    @Mock private SpatialNodeService spatialNodeService;
    @Mock private InspectionEventRecorder events;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private UserContextService userContextService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ObservationMapper mapper;
    @Mock private ProjectRepository projectRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private TransactionRetryTemplate retryTemplate;
    @Mock private ObservationRegionRepository regionRepo;
    @Mock private ObjectProvider<ObservationIntakeCheck> intakeChecks;
    @Mock private ObservationIntakeUploadRepository intakeUploads;

    private ObservationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new ObservationService(observationRepo, inspectionRepo, spatialNodeService, events,
                tenantEntityHelper, userContextService, employeeRepository, mapper, projectRepository,
                attachmentRepository, retryTemplate, regionRepo, intakeChecks, intakeUploads);
        lenient().when(retryTemplate.execute(anyString(), any(), any(Supplier.class)))
                .thenAnswer(call -> call.getArgument(2, Supplier.class).get());
        lenient().when(observationRepo.findByExternalRefScoped(anyString())).thenReturn(Optional.empty());
        lenient().when(projectRepository.existsByIdAndOrganization_Id(PROJECT, ORG)).thenReturn(true);
        Attachment image = new Attachment();
        image.setId(IMAGE);
        image.setEntityType("INSPECTION_EVIDENCE");
        image.setContentType("image/jpeg");
        Project project = new Project();
        project.setId(PROJECT);
        image.setProject(project);
        lenient().when(attachmentRepository.findByIdAndOrganization_Id(IMAGE, ORG)).thenReturn(Optional.of(image));
        lenient().when(observationRepo.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(regionRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        ObservationDto dto = mock(ObservationDto.class);
        lenient().when(dto.withRegions(any())).thenReturn(dto);
        lenient().when(mapper.toDto(any())).thenReturn(dto);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void everyCheckSeesTheFindingBeforeAnythingIsWritten() {
        List<IntakeCandidate> seen = new ArrayList<>();
        when(intakeChecks.orderedStream()).thenReturn(Stream.of(seen::add, seen::add));

        service.intake(request(null, "ppe", "0.1.0", new BigDecimal("0.81")));

        assertThat(seen).hasSize(2);
        IntakeCandidate candidate = seen.getFirst();
        assertThat(candidate.source()).isEqualTo("AI");
        assertThat(candidate.modelName()).isEqualTo("ppe");
        assertThat(candidate.modelVersion()).isEqualTo("0.1.0");
        assertThat(candidate.regions()).singleElement().satisfies(region -> {
            assertThat(region.classKey()).isEqualTo("ppe_helmet");
            assertThat(region.shape()).isEqualTo("BOX");
            assertThat(region.score()).isEqualByComparingTo("0.81");
        });
    }

    @Test
    void aRefusalStopsTheFindingBeforeItIsStored() {
        ObservationIntakeCheck refuses = candidate -> {
            throw new IntakeRejectedException("UNKNOWN_CLASS", "regions[0].classKey", "No such class");
        };
        when(intakeChecks.orderedStream()).thenReturn(Stream.of(refuses));

        assertThatThrownBy(() -> service.intake(request(null, "ppe", "0.1.0", null)))
                .isInstanceOf(IntakeRejectedException.class)
                .hasFieldOrPropertyWithValue("code", "UNKNOWN_CLASS");

        verify(observationRepo, never()).saveAndFlush(any());
    }

    @Test
    void withNoCheckInstalledTheFindingIsStored() {
        when(intakeChecks.orderedStream()).thenReturn(Stream.of());

        service.intake(request(null, "ppe", "0.1.0", null));

        verify(observationRepo).saveAndFlush(any());
    }

    @Test
    void aRepeatOfAnExternalReferenceIsNotCheckedAgain() {
        Observation stored = new Observation();
        stored.setId(UUID.randomUUID());
        when(observationRepo.findByExternalRefScoped("cap-1")).thenReturn(Optional.of(stored));
        when(observationRepo.findByIdScoped(stored.getId())).thenReturn(Optional.of(stored));
        when(regionRepo.findByObservationScoped(stored.getId())).thenReturn(List.of());

        service.intake(request(null, "ppe", "0.1.0", null));

        verify(intakeChecks, never()).orderedStream();
    }

    @Test
    void aModelsFindingWithNoCategoryIsFiledUnderItsFirstRegion() {
        when(intakeChecks.orderedStream()).thenReturn(Stream.of());

        service.intake(request(null, "ppe", "0.1.0", null));

        assertThat(storedObservation().getCategory()).isEqualTo("ppe_helmet");
    }

    @Test
    void aCategoryTheProducerGaveIsKept() {
        when(intakeChecks.orderedStream()).thenReturn(Stream.of());

        service.intake(request("ppe", "ppe", "0.1.0", null));

        assertThat(storedObservation().getCategory()).isEqualTo("ppe");
    }

    @Test
    void aDeviceFindingWithNoScoreIsNotGivenACategory() {
        when(intakeChecks.orderedStream()).thenReturn(Stream.of());

        service.intake(new IntakeObservationRequest(PROJECT, "cap-1", ObservationSource.DRONE, "drone-1", null, null,
                null, null, LocalDateTime.of(2026, 11, 8, 9, 0), "Seen", null, null, null, null, null, null, null,
                List.of(region(null))));

        assertThat(storedObservation().getCategory()).isNull();
    }

    @Test
    void aRefusalReachesTheProducerWithItsCode() throws Exception {
        ObservationService refusing = mock(ObservationService.class);
        when(refusing.intake(any())).thenThrow(
                new IntakeRejectedException("MODEL_NOT_ACTIVE", "modelVersion", "Version 0.1.0 of ppe is not active"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ObservationIntakeController(refusing, mock(ObservationIntakeUploadService.class),
                mock(ObservationBatchIntakeService.class)))
                .setControllerAdvice(new InspectionsExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/observations/intake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":42,"externalRef":"cap-1","source":"ai","sourceDeviceId":"d",
                                 "observedAt":"2026-11-08T09:00:00","title":"t"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_ACTIVE"))
                .andExpect(jsonPath("$.field").value("modelVersion"))
                .andExpect(jsonPath("$.message").value("Version 0.1.0 of ppe is not active"));
    }

    private Observation storedObservation() {
        ArgumentCaptor<Observation> saved = ArgumentCaptor.forClass(Observation.class);
        verify(observationRepo).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private static IntakeObservationRequest request(String category, String modelName, String modelVersion,
                                                    BigDecimal score) {
        return new IntakeObservationRequest(PROJECT, "cap-1", ObservationSource.AI, "worker-1", null, null, null, null,
                LocalDateTime.of(2026, 11, 8, 9, 0), "Helmet detected", null, category, null, modelName, modelVersion,
                score, null, List.of(region(score)));
    }

    private static ObservationRegionRequest region(BigDecimal score) {
        return new ObservationRegionRequest(IMAGE, RegionShape.BOX,
                List.of(new BigDecimal("0.1"), new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.2")),
                null, "ppe_helmet", score, 4032, 3024);
    }
}

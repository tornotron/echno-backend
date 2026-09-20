package org.tornotron.echno_backend.modules.inspections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.domain.Ncr;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionReference;
import org.tornotron.echno_backend.modules.inspections.dtos.NcrDto;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventRecorder;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapper;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.NcrRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ReinspectionRepository;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.modules.inspections.service.ObservationService;
import org.tornotron.echno_backend.project.ProjectName;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A report and a defect each say which inspection and which project they came from, and the
 * answer is read through the inspection: the report stores only the inspection's id, the
 * defect only its parent, and neither takes a project from a client. This pins the mapping
 * and the two batched reads behind a page of reports.
 */
@ExtendWith(MockitoExtension.class)
class NcrProjectTraceTest {

    private static final UUID INSPECTION_A = UUID.randomUUID();
    private static final UUID INSPECTION_B = UUID.randomUUID();
    private static final UUID GONE = UUID.randomUUID();

    @Mock private NcrRepository ncrRepo;
    @Mock private InspectionRepository inspectionRepo;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserContextService userContextService;
    @Mock private EntryNumberGenerator numberGen;
    @Spy private NcrMapper mapper = new NcrMapperImpl();
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private InspectionEventRecorder events;
    @Mock private ReinspectionRepository reinspectionRepo;
    @Mock private ObservationService observations;
    @Mock private ProjectRepository projectRepository;

    @InjectMocks
    private NcrService service;

    @Test
    void aPageOfReportsIsTracedWithOneInspectionReadAndOneProjectRead() {
        Ncr onA = ncr("NCR-1", INSPECTION_A);
        Ncr alsoOnA = ncr("NCR-2", INSPECTION_A);
        Ncr onB = ncr("NCR-3", INSPECTION_B);
        Ncr orphan = ncr("NCR-4", GONE);
        Pageable pageable = PageRequest.of(0, 10);
        when(ncrRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(onA, alsoOnA, onB, orphan), pageable, 4));
        when(inspectionRepo.findReferencesByIdsScoped(List.of(INSPECTION_A, INSPECTION_B, GONE)))
                .thenReturn(List.of(
                        new InspectionReference(INSPECTION_A, "INS-2026-0001", "Slab check", 3L),
                        new InspectionReference(INSPECTION_B, "INS-2026-0002", "Annex check", null)));
        when(projectRepository.findNamesByIds(List.of(3L)))
                .thenReturn(List.of(new ProjectName(3L, "Tower B")));

        Page<NcrDto> page = service.findAll(3L, null, null, null, null, null, null, null, null, pageable);

        List<NcrDto> rows = page.getContent();
        assertThat(rows).extracting(NcrDto::ncrNumber)
                .containsExactly("NCR-1", "NCR-2", "NCR-3", "NCR-4");
        assertThat(rows.get(0).inspectionNumber()).isEqualTo("INS-2026-0001");
        assertThat(rows.get(0).inspectionTitle()).isEqualTo("Slab check");
        assertThat(rows.get(0).projectId()).isEqualTo(3L);
        assertThat(rows.get(0).projectName()).isEqualTo("Tower B");
        assertThat(rows.get(1).projectName()).isEqualTo("Tower B");
        assertThat(rows.get(2).inspectionTitle()).isEqualTo("Annex check");
        assertThat(rows.get(2).projectId())
                .as("an inspection recorded without a project traces to none")
                .isNull();
        assertThat(rows.get(2).projectName()).isNull();
        assertThat(rows.get(3).inspectionNumber())
                .as("a report whose inspection cannot be read keeps its trace empty")
                .isNull();
        assertThat(rows.get(3).projectId()).isNull();
        // Two reads for four rows, and the inspection ids are sent once each.
        verify(inspectionRepo).findReferencesByIdsScoped(List.of(INSPECTION_A, INSPECTION_B, GONE));
        verify(projectRepository).findNamesByIds(List.of(3L));
    }

    @Test
    void anEmptyPageReadsNothing() {
        Pageable pageable = PageRequest.of(0, 10);
        when(ncrRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(service.findAll(null, null, null, null, null, null, null, null, null, pageable))
                .isEmpty();

        verify(inspectionRepo, never()).findReferencesByIdsScoped(anyCollection());
        verify(projectRepository, never()).findNamesByIds(anyCollection());
    }

    @Test
    void aDefectCarriesItsParentInspectionAndThatInspectionsProject() {
        Inspection inspection = new Inspection();
        inspection.setId(INSPECTION_A);
        inspection.setInspectionNumber("INS-2026-0001");
        inspection.setTitle("Slab check");
        inspection.setProjectId(3L);
        InspectionDefect defect = new InspectionDefect();
        defect.setId(UUID.randomUUID());
        defect.setDescription("Honeycombing on column C4");
        defect.setCorrectiveAction("Chip out and re-pour");
        defect.setInspection(inspection);

        InspectionDefectDto dto = new InspectionMapperImpl().toDefectDto(defect);

        assertThat(dto.inspectionId()).isEqualTo(INSPECTION_A);
        assertThat(dto.inspectionNumber()).isEqualTo("INS-2026-0001");
        assertThat(dto.inspectionTitle()).isEqualTo("Slab check");
        assertThat(dto.projectId()).isEqualTo(3L);
        assertThat(dto.projectName())
                .as("the name is not reachable from the entity; the service fills it")
                .isNull();
        assertThat(dto.withProjectName("Tower B").projectName()).isEqualTo("Tower B");
        assertThat(dto.withProjectName("Tower B").inspectionId()).isEqualTo(INSPECTION_A);
    }

    private static Ncr ncr(String number, UUID inspectionId) {
        Ncr ncr = new Ncr();
        ncr.setId(UUID.randomUUID());
        ncr.setNcrNumber(number);
        ncr.setType(NcrType.QUALITY);
        ncr.setInspectionId(inspectionId);
        ncr.setTitle(number);
        ncr.setDescription("described");
        return ncr;
    }
}

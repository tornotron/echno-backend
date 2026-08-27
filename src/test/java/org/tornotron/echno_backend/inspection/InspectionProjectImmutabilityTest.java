package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapper;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.inspection.service.ChecklistTemplateService;
import org.tornotron.echno_backend.inspection.service.InspectionService;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the project on an inspection as fixed from creation. A statutory approval
 * has to keep a permanent relationship with the project it was obtained for, and
 * a compliance inspection is additionally identified by its project, so an update
 * that repoints one is refused rather than silently applied.
 *
 * <p>Plain Mockito on purpose: the rule is a comparison inside the service and
 * needs no Spring context, and every distinct context the suite loads is cached
 * for the life of the JVM.
 */
@ExtendWith(MockitoExtension.class)
class InspectionProjectImmutabilityTest {

    private static final UUID INSPECTION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long STORED_PROJECT = 14L;

    @Mock
    private InspectionRepository inspectionRepo;
    @Mock
    private EntryNumberGenerator numberGen;
    @Mock
    private InspectionMapper mapper;
    @Mock
    private TenantEntityHelper tenantEntityHelper;
    @Mock
    private ChecklistTemplateService checklistTemplateService;

    @InjectMocks
    private InspectionService service;

    @Test
    void update_rejectsAMoveToAnotherProject() {
        Inspection stored = storedInspection();
        when(inspectionRepo.findByIdScoped(INSPECTION_ID)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.update(INSPECTION_ID, requestForProject(99L)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("INSP-0007")
                .hasMessageContaining("cannot be moved to project 99");

        // The refusal has to happen before anything is written, or a rejected
        // request would still leave the other fields replaced.
        assertThat(stored.getProjectId()).isEqualTo(STORED_PROJECT);
        assertThat(stored.getTitle()).isEqualTo("Building Plan Approval");
        verify(inspectionRepo, never()).saveAndFlush(any());
    }

    @Test
    void update_acceptsThePayloadThatRepeatsTheStoredProject() {
        // The web client sends the whole record back on every save, so the stored
        // id arriving unchanged is the normal edit, not an attempted reassignment.
        Inspection stored = storedInspection();
        when(inspectionRepo.findByIdScoped(INSPECTION_ID)).thenReturn(Optional.of(stored));
        when(inspectionRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(INSPECTION_ID, requestForProject(STORED_PROJECT));

        assertThat(stored.getProjectId()).isEqualTo(STORED_PROJECT);
        assertThat(stored.getTitle()).isEqualTo("Site check");
        verify(inspectionRepo).saveAndFlush(stored);
    }

    @Test
    void update_leavesTheProjectInPlaceWhenThePayloadOmitsIt() {
        Inspection stored = storedInspection();
        when(inspectionRepo.findByIdScoped(INSPECTION_ID)).thenReturn(Optional.of(stored));
        when(inspectionRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(INSPECTION_ID, requestForProject(null));

        assertThat(stored.getProjectId()).isEqualTo(STORED_PROJECT);
        verify(inspectionRepo).saveAndFlush(stored);
    }

    @Test
    void update_stillReportsAnInspectionThatDoesNotExist() {
        when(inspectionRepo.findByIdScoped(INSPECTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(INSPECTION_ID, requestForProject(99L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Inspection storedInspection() {
        Inspection inspection = new Inspection();
        inspection.setInspectionNumber("INSP-0007");
        inspection.setTitle("Building Plan Approval");
        inspection.setType(InspectionType.COMPLIANCE);
        inspection.setCategory(InspectionCategory.COMPLIANCE);
        inspection.setStatus(InspectionStatus.SUGGESTED);
        inspection.setOrigin(InspectionOrigin.AI_GENERATED);
        inspection.setComplianceRuleRef("TN-RES-001");
        inspection.setProjectId(STORED_PROJECT);
        return inspection;
    }

    private UpdateInspectionRequest requestForProject(Long projectId) {
        return new UpdateInspectionRequest(
                "Site check",
                InspectionType.QUALITY,
                InspectionCategory.QA_QC,
                InspectionTrade.RCC,
                InspectionStatus.COMPLETED,
                InspectionResult.PASSED,
                projectId,
                "Block A",
                "Level 3",
                "STR-03",
                LocalDate.of(2026, 8, 25),
                "09:30",
                null, null, 60,
                100L,
                200L,
                "Client Rep",
                List.of("Site Engineer"),
                "Clear",
                "30C",
                List.of(),
                List.of());
    }
}

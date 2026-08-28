package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.domain.InspectionDefect;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationRequest;
import org.tornotron.echno_backend.inspection.dtos.ReplaceAnnotationsRequest;
import org.tornotron.echno_backend.inspection.mapper.DefectPhotoAnnotationMapper;
import org.tornotron.echno_backend.inspection.repositories.DefectPhotoAnnotationRepository;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.inspection.service.DefectAnnotationService;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two rules that make an annotation belong to a photograph rather than to a
 * defect row: a mark may only name a photo one of the inspection's defects
 * actually carries, and a mark whose photo is no longer attached is deleted.
 *
 * <p>Plain Mockito on purpose. Both rules are decisions the service makes before
 * it writes anything, so neither needs a database and neither needs a Spring
 * context; the test JVM caches every context it loads for the whole run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefectAnnotationServiceTest {

    private static final UUID INSPECTION_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long ORG_ID = 42L;
    private static final String KEPT = "https://cdn.example.test/inspections/kept.jpg";
    private static final String REPLACED = "https://cdn.example.test/inspections/replaced.jpg";

    @Mock
    private DefectPhotoAnnotationRepository annotationRepo;
    @Mock
    private InspectionRepository inspectionRepo;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private UserContextService userContextService;
    @Mock
    private DefectPhotoAnnotationMapper mapper;
    @Mock
    private TenantEntityHelper tenantEntityHelper;

    @InjectMocks
    private DefectAnnotationService service;

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void refusesAMarkOnAPhotoNoDefectOnTheInspectionCarries() {
        when(inspectionRepo.findByIdScoped(INSPECTION_ID))
                .thenReturn(Optional.of(inspectionWithPhotos(KEPT)));

        assertThatThrownBy(() -> service.replaceForInspection(INSPECTION_ID,
                new ReplaceAnnotationsRequest(List.of(markOn(REPLACED)))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("has no defect photo");

        verify(annotationRepo, never()).deleteByInspection(anyLong(), any());
        verify(annotationRepo, never()).saveAll(any());
    }

    /**
     * A refused payload must not have cleared what was already stored. The replace
     * deletes before it writes, so validating everything up front is what keeps a
     * single bad mark in an otherwise good payload from wiping the mark-up.
     */
    @Test
    void refusesTheWholePayloadBeforeItDeletesAnything() {
        when(inspectionRepo.findByIdScoped(INSPECTION_ID))
                .thenReturn(Optional.of(inspectionWithPhotos(KEPT)));

        assertThatThrownBy(() -> service.replaceForInspection(INSPECTION_ID,
                new ReplaceAnnotationsRequest(List.of(markOn(KEPT), markOn(REPLACED)))))
                .isInstanceOf(InvalidRequestException.class);

        verify(annotationRepo, never()).deleteByInspection(anyLong(), any());
    }

    @Test
    void refusesAnInspectionThatIsNotInThisTenant() {
        when(inspectionRepo.findByIdScoped(INSPECTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceForInspection(INSPECTION_ID,
                new ReplaceAnnotationsRequest(List.of(markOn(KEPT)))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * The whole point of keying a mark by its photograph. The photo that is still
     * attached keeps its marks; the one that was replaced loses them, because the
     * coordinates describe a region of that image and on a different one they point
     * at nothing while still reading as evidence.
     */
    @Test
    void sweepsTheMarksWhosePhotoIsNoLongerAttached() {
        Inspection inspection = inspectionWithPhotos(KEPT);
        inspection.setId(INSPECTION_ID);

        service.removeOrphaned(inspection);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> kept = ArgumentCaptor.forClass(Collection.class);
        verify(annotationRepo).deleteOrphansByInspection(eq(ORG_ID), eq(INSPECTION_ID),
                kept.capture());
        assertThat(kept.getValue()).containsExactly(KEPT);
        verify(annotationRepo, never()).deleteByInspection(anyLong(), any());
    }

    /**
     * Every photograph gone means every mark goes. The sweep cannot express that
     * as "not in the empty set", because {@code NOT IN ()} is not a query, so the
     * service has to pick the other statement.
     */
    @Test
    void dropsEveryMarkWhenNoPhotographIsLeft() {
        Inspection inspection = inspectionWithPhotos();
        inspection.setId(INSPECTION_ID);

        service.removeOrphaned(inspection);

        verify(annotationRepo).deleteByInspection(ORG_ID, INSPECTION_ID);
        verify(annotationRepo, never()).deleteOrphansByInspection(anyLong(), any(), any());
    }

    @Test
    void anEmptyRequestClearsTheMarksWithoutWritingAny() {
        when(inspectionRepo.findByIdScoped(INSPECTION_ID))
                .thenReturn(Optional.of(inspectionWithPhotos(KEPT)));

        assertThat(service.replaceForInspection(INSPECTION_ID,
                new ReplaceAnnotationsRequest(List.of()))).isEmpty();

        verify(annotationRepo).deleteByInspection(ORG_ID, INSPECTION_ID);
        verify(annotationRepo, never()).saveAll(any());
    }

    private static DefectPhotoAnnotationRequest markOn(String photo) {
        return new DefectPhotoAnnotationRequest(photo, DefectAnnotationShape.RECTANGLE,
                new BigDecimal("0.1"), new BigDecimal("0.1"),
                new BigDecimal("0.4"), new BigDecimal("0.5"), "Honeycombing");
    }

    private static Inspection inspectionWithPhotos(String... photos) {
        Inspection inspection = new Inspection();
        inspection.setInspectionNumber("INSP-2026-0001");
        InspectionDefect defect = new InspectionDefect();
        defect.getPhotos().addAll(List.of(photos));
        inspection.getDefects().add(defect);
        return inspection;
    }
}

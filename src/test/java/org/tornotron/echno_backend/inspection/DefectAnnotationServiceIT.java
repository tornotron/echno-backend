package org.tornotron.echno_backend.inspection;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.inspection.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationDto;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDefectRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.dtos.ReplaceAnnotationsRequest;
import org.tornotron.echno_backend.inspection.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.inspection.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.inspection.service.ChecklistTemplateService;
import org.tornotron.echno_backend.inspection.service.DefectAnnotationService;
import org.tornotron.echno_backend.inspection.service.InspectionService;
import org.tornotron.echno_backend.inspection.service.NcrService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The marks drawn over defect photographs, against a real CockroachDB.
 *
 * <p>The test that matters here is
 * {@link #anUpdateKeepsTheMarksOnThePhotosItKeepsAndDropsTheRest}. An inspection
 * update deletes and reinserts every defect row, so a mark modelled as a child of
 * the defect would be destroyed on the next save an inspector makes and this whole
 * feature would look like it worked until somebody edited a remark. Keying the
 * mark on the photograph is what makes it survive, and dropping the marks whose
 * photograph is gone is what stops one surviving onto a picture it does not
 * describe.
 *
 * <p>The annotations and the {@code @Import} list repeat {@link InspectionServiceIT}
 * to the letter on purpose, so Spring's context cache serves every inspection test
 * class from one context. See that class for why that matters in a 1 GB test JVM.
 * Keep them in step when any one changes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        NcrService.class, NcrMapperImpl.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class DefectAnnotationServiceIT extends AbstractIntegrationTest {

    private static final String KEPT = "https://cdn.example.test/inspections/kept.jpg";
    private static final String REPLACED = "https://cdn.example.test/inspections/replaced.jpg";

    @Autowired
    private DefectAnnotationService service;

    @Autowired
    private InspectionService inspectionService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Annotation Org A");
            Organization orgB = persistOrganization("Annotation Org B");

            Project project = new Project();
            project.setProjectName("Tower C");
            project.setOrganization(orgA);
            entityManager.persist(project);

            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
        });
        TenantContext.setCurrentOrgId(orgAId);
    }

    @AfterEach
    void clearTenantState() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    @AfterTransaction
    void removeCommittedRows() {
        if (orgAId == null && orgBId == null) {
            return;
        }
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM inspection_defect_annotations WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM inspection_defects WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspection_check_items WHERE inspection_id IN "
                    + "(SELECT id FROM inspections WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM inspections WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    @Test
    void storesTheMarksAndReadsThemBackInDrawOrder() {
        InspectionDto inspection = inspectionWithPhotos(KEPT, REPLACED);

        service.replaceForInspection(inspection.id(), new ReplaceAnnotationsRequest(List.of(
                markOn(KEPT, "First"), markOn(KEPT, "Second"), markOn(REPLACED, "Third"))));

        List<DefectPhotoAnnotationDto> stored =
                service.findByInspection(inspection.id(), PageRequest.of(0, 50)).getContent();

        assertThat(stored).hasSize(3);
        assertThat(stored).extracting(DefectPhotoAnnotationDto::photo)
                .containsExactly(KEPT, KEPT, REPLACED);
        assertThat(stored).extracting(DefectPhotoAnnotationDto::label)
                .containsExactly("First", "Second", "Third");
    }

    /**
     * The whole design in one test. An update rebuilds the defects from the payload,
     * so every defect row is replaced; the marks on the photograph the payload keeps
     * have to survive that, and the marks on the photograph it dropped have to go.
     */
    @Test
    void anUpdateKeepsTheMarksOnThePhotosItKeepsAndDropsTheRest() {
        InspectionDto inspection = inspectionWithPhotos(KEPT, REPLACED);
        UUID defectIdBefore = inspection.defects().getFirst().id();

        service.replaceForInspection(inspection.id(), new ReplaceAnnotationsRequest(List.of(
                markOn(KEPT, "Still here"), markOn(REPLACED, "Goes with its photo"))));

        InspectionDto updated = inspectionService.update(inspection.id(),
                updateWithPhotos(inspection, KEPT));

        // the defect row really was replaced, which is what a child of it would not survive
        assertThat(updated.defects().getFirst().id()).isNotEqualTo(defectIdBefore);

        List<DefectPhotoAnnotationDto> stored =
                service.findByInspection(inspection.id(), PageRequest.of(0, 50)).getContent();
        assertThat(stored).extracting(DefectPhotoAnnotationDto::photo).containsExactly(KEPT);
        assertThat(stored).extracting(DefectPhotoAnnotationDto::label).containsExactly("Still here");
    }

    @Test
    void anUpdateThatRemovesEveryPhotoDropsEveryMark() {
        InspectionDto inspection = inspectionWithPhotos(KEPT);
        service.replaceForInspection(inspection.id(),
                new ReplaceAnnotationsRequest(List.of(markOn(KEPT, "Only mark"))));

        inspectionService.update(inspection.id(), updateWithPhotos(inspection));

        assertThat(service.findByInspection(inspection.id(), PageRequest.of(0, 50)))
                .isEmpty();
    }

    @Test
    void refusesAMarkOnAPhotoNoDefectCarries() {
        InspectionDto inspection = inspectionWithPhotos(KEPT);

        assertThatThrownBy(() -> service.replaceForInspection(inspection.id(),
                new ReplaceAnnotationsRequest(List.of(markOn(REPLACED, "Nowhere")))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("has no defect photo");
    }

    @Test
    void replacingTheSetDiscardsWhatWasThereBefore() {
        InspectionDto inspection = inspectionWithPhotos(KEPT);
        service.replaceForInspection(inspection.id(), new ReplaceAnnotationsRequest(
                List.of(markOn(KEPT, "Old one"), markOn(KEPT, "Old two"))));

        service.replaceForInspection(inspection.id(),
                new ReplaceAnnotationsRequest(List.of(markOn(KEPT, "New one"))));

        assertThat(service.findByInspection(inspection.id(), PageRequest.of(0, 50)).getContent())
                .extracting(DefectPhotoAnnotationDto::label)
                .containsExactly("New one");
    }

    /**
     * The database refuses a coordinate that is not a fraction of the image, whatever
     * wrote it. The request validation catches a bad payload; this catches a bad row.
     */
    @Test
    void theDatabaseRefusesACoordinateOffTheImage() {
        InspectionDto inspection = inspectionWithPhotos(KEPT);

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "INSERT INTO inspection_defect_annotations "
                                    + "(id, organization_id, inspection_id, photo, shape, "
                                    + " x1, y1, x2, y2, line_order) "
                                    + "VALUES (gen_random_uuid(), :org, :inspection, :photo, "
                                    + " 'RECTANGLE', 0.1, 0.1, 1.4, 0.5, 0)")
                    .setParameter("org", orgAId)
                    .setParameter("inspection", inspection.id())
                    .setParameter("photo", KEPT)
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    private InspectionDto inspectionWithPhotos(String... photos) {
        return inspectionService.create(new CreateInspectionRequest(
                "Slab check", InspectionType.QUALITY, null, null, projectId,
                "Block A", null, null, LocalDate.of(2026, 8, 20), null,
                null, null, null, 100L, null, null, null, null, null,
                null,
                List.of(new InspectionDefectRequest("Structural", "Honeycombing on column C4",
                        DefectSeverity.MAJOR, "Grid C4", List.of(photos),
                        "Chip out and re-pour", "Contractor",
                        LocalDate.of(2026, 9, 1), null, null))));
    }

    private static UpdateInspectionRequest updateWithPhotos(InspectionDto inspection,
                                                            String... photos) {
        return new UpdateInspectionRequest(
                inspection.title(), inspection.type(), inspection.category(), inspection.trade(),
                inspection.status(), inspection.result(), inspection.projectId(),
                inspection.location(), inspection.areaInspected(), inspection.drawingReference(),
                inspection.scheduledDate(), inspection.scheduledTime(),
                inspection.actualStartTime(), inspection.actualEndTime(), inspection.duration(),
                inspection.inspectorId(), inspection.contractorId(),
                inspection.clientRepresentative(), inspection.attendees(),
                inspection.weatherConditions(), inspection.temperature(),
                null,
                List.of(new InspectionDefectRequest("Structural", "Honeycombing on column C4",
                        DefectSeverity.MAJOR, "Grid C4", List.of(photos),
                        "Chip out and re-pour", "Contractor",
                        LocalDate.of(2026, 9, 1), null, null)));
    }

    private static DefectPhotoAnnotationRequest markOn(String photo, String label) {
        return new DefectPhotoAnnotationRequest(photo, DefectAnnotationShape.RECTANGLE,
                new BigDecimal("0.10"), new BigDecimal("0.20"),
                new BigDecimal("0.40"), new BigDecimal("0.55"), label);
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql)
                .setParameter("a", orgAId)
                .setParameter("b", orgBId)
                .executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }
}

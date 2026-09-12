package org.tornotron.echno_backend.modules.inspections;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.DefectPhotoAnnotationMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.NcrService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two schema repairs in 094, asserted against a real CockroachDB rather than against the
 * entity annotations.
 *
 * <p>Both have to be measured this way. {@code ddl-auto} is {@code validate} and Hibernate does
 * not compare nullability, so an entity and its column can disagree in either direction without
 * the build noticing, and a test that builds a defect the way production does would only observe
 * the field initialiser it set itself. The insert below names every column except {@code status}
 * precisely so the database is the thing answering.
 *
 * <p>Before 094 the first test reads back {@code open}, the {@code @JsonValue} spelling that
 * {@code @Enumerated(STRING)} cannot read, and the second fails with an
 * {@code IllegalArgumentException} on the enum conversion. The third fails on both columns.
 *
 * <p>The Spring configuration is deliberately identical to {@link InspectionServiceIT} and
 * {@link InspectionTaxonomyMigrationIT} so every inspection test class shares one cached
 * application context rather than starting a second. Keep it in step when that list changes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        NcrService.class, NcrMapperImpl.class,
        DefectAnnotationService.class, DefectPhotoAnnotationMapperImpl.class,
        UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class InspectionNullabilityRepairIT extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void aDefectWrittenWithoutAStatusTakesTheNameTheEnumPersists() {
        UUID defectId = insertDefectWithoutStatus(insertInspection(insertOrganization()));

        assertThat(readStatus(defectId)).isEqualTo("OPEN");
    }

    @Test
    void thatRowThenLoadsThroughTheEntity() {
        UUID defectId = insertDefectWithoutStatus(insertInspection(insertOrganization()));
        entityManager.flush();
        entityManager.clear();

        InspectionDefect defect = entityManager.find(InspectionDefect.class, defectId);

        assertThat(defect.getStatus()).isEqualTo(DefectStatus.OPEN);
    }

    @Test
    void bothColumnsAreConstrainedTheWayTheirSiblingsAre() {
        // inspections.status, inspection_check_items.status and ncrs.status all carry NOT NULL,
        // and ncrs, checklist_templates and inspection_defect_annotations all constrain their
        // organization_id. These two were the exceptions.
        assertThat(isNullable("inspection_defects", "status")).isEqualTo("NO");
        assertThat(isNullable("inspections", "organization_id")).isEqualTo("NO");
    }

    private Long insertOrganization() {
        return ((Number) entityManager.createNativeQuery(
                        "INSERT INTO organization (organization_name, organization_address, "
                                + "organization_email, organization_phone) "
                                + "VALUES (:name, :address, :email, :phone) RETURNING id")
                .setParameter("name", "Nullability repair " + UUID.randomUUID())
                .setParameter("address", "Chennai")
                .setParameter("email", "qa@example.test")
                .setParameter("phone", "9847012345")
                .getSingleResult()).longValue();
    }

    private UUID insertInspection(Long organizationId) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery(
                        "INSERT INTO inspections (id, inspection_number, title, type, "
                                + "scheduled_date, organization_id) "
                                + "VALUES (CAST(:id AS UUID), :number, :title, :type, "
                                + ":scheduledDate, :organizationId)")
                .setParameter("id", id.toString())
                .setParameter("number", "INSP-" + id.toString().substring(0, 8))
                .setParameter("title", "Defect status default")
                .setParameter("type", InspectionType.QUALITY.name())
                .setParameter("scheduledDate", LocalDate.of(2026, 9, 8))
                .setParameter("organizationId", organizationId)
                .executeUpdate();
        return id;
    }

    /**
     * Names every column the row needs except {@code status}, which is the whole point: the
     * value that ends up there is the one the DEFAULT clause supplies.
     */
    private UUID insertDefectWithoutStatus(UUID inspectionId) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery(
                        "INSERT INTO inspection_defects (id, inspection_id, description, "
                                + "corrective_action, line_order) "
                                + "VALUES (CAST(:id AS UUID), CAST(:inspectionId AS UUID), "
                                + ":description, :correctiveAction, :lineOrder)")
                .setParameter("id", id.toString())
                .setParameter("inspectionId", inspectionId.toString())
                .setParameter("description", "Cracked render at the stair core")
                .setParameter("correctiveAction", "Cut out and make good")
                .setParameter("lineOrder", 1)
                .executeUpdate();
        return id;
    }

    private String readStatus(UUID defectId) {
        return (String) entityManager.createNativeQuery(
                        "SELECT status FROM inspection_defects WHERE id = CAST(:id AS UUID)")
                .setParameter("id", defectId.toString())
                .getSingleResult();
    }

    private String isNullable(String table, String column) {
        return (String) entityManager.createNativeQuery(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_schema = 'public' AND table_name = :table "
                                + "AND column_name = :column")
                .setParameter("table", table)
                .setParameter("column", column)
                .getSingleResult();
    }
}

package org.tornotron.echno_backend.inspection;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.inspection.domain.InspectionDefect;
import org.tornotron.echno_backend.inspection.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.inspection.mapper.NcrMapperImpl;
import org.tornotron.echno_backend.inspection.service.ChecklistTemplateService;
import org.tornotron.echno_backend.inspection.service.InspectionService;
import org.tornotron.echno_backend.inspection.service.NcrService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the 054 taxonomy migration by running its own statements, read out of
 * the changelog file itself, against a real CockroachDB. Seeding legacy-shaped rows
 * and then applying the changelog's SQL is the only way to cover a backfill: by the
 * time the application's Liquibase run has finished on a fresh database there are no
 * rows for it to touch.
 *
 * <p>The Spring configuration is deliberately identical to {@link InspectionServiceIT}
 * so every inspection test class shares one cached application context rather than
 * starting a second. Keep it in step when that list changes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InspectionService.class, InspectionMapperImpl.class,
        ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        NcrService.class, NcrMapperImpl.class, UserContextService.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class InspectionTaxonomyMigrationIT extends AbstractIntegrationTest {

    private static final String CHANGELOG =
            "db/changelog/v4.0/054-add-inspection-taxonomy-and-defect-enums.xml";

    private static final String BACKFILL_CATEGORY = "054-02-backfill-inspection-category";
    private static final String BLANK_DEFECT_FIELDS = "054-03-blank-defect-severity-and-status";
    private static final String PROMOTE_SEVERITY = "054-04-promote-defect-severity-to-enum";
    private static final String PROMOTE_STATUS = "054-05-promote-defect-status-to-enum";

    /** Changeset id to the SQL statements it runs, in declaration order. */
    private static Map<String, List<String>> statements;

    /** Changeset id to its precondition sqlCheck, where it has one. */
    private static Map<String, String> preconditions;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeAll
    static void readChangelog() throws Exception {
        statements = new HashMap<>();
        preconditions = new HashMap<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document document;
        try (InputStream in = InspectionTaxonomyMigrationIT.class.getClassLoader()
                .getResourceAsStream(CHANGELOG)) {
            assertThat(in).as("changelog %s on the test classpath", CHANGELOG).isNotNull();
            document = builder.parse(new InputSource(in));
        }

        NodeList changeSets = document.getElementsByTagName("changeSet");
        for (int i = 0; i < changeSets.getLength(); i++) {
            Element changeSet = (Element) changeSets.item(i);
            String id = changeSet.getAttribute("id");

            List<String> sql = new ArrayList<>();
            NodeList sqlNodes = changeSet.getElementsByTagName("sql");
            for (int j = 0; j < sqlNodes.getLength(); j++) {
                sql.add(sqlNodes.item(j).getTextContent().trim());
            }
            statements.put(id, sql);

            NodeList checks = changeSet.getElementsByTagName("sqlCheck");
            if (checks.getLength() > 0) {
                preconditions.put(id, checks.item(0).getTextContent().trim());
            }
        }
    }

    @Test
    void categoryBackfill_movesEveryLegacyTypeIntoItsCategory() {
        Map<InspectionType, UUID> ids = new HashMap<>();
        for (InspectionType type : InspectionType.values()) {
            ids.put(type, insertLegacyInspection(type));
        }

        // Every row starts on the column default the migration adds it with.
        for (UUID id : ids.values()) {
            assertThat(readCategory(id)).isEqualTo("OTHER");
        }

        run(BACKFILL_CATEGORY);

        for (InspectionType type : InspectionType.values()) {
            assertThat(readCategory(ids.get(type)))
                    .as("backfilled category for type %s", type)
                    .isEqualTo(InspectionCategory.defaultFor(type).name());
        }
    }

    @Test
    void categoryBackfill_isIdempotentAndLeavesAStatedCategoryAlone() {
        UUID id = insertLegacyInspection(InspectionType.QUALITY);

        run(BACKFILL_CATEGORY);
        assertThat(readCategory(id)).isEqualTo("QA_QC");

        // A second application changes nothing, and a row already moved off the
        // default is not revisited.
        run(BACKFILL_CATEGORY);
        assertThat(readCategory(id)).isEqualTo("QA_QC");
    }

    @Test
    void defectPromotion_normalisesTheFreeTextTheColumnsHeld() {
        UUID inspectionId = insertLegacyInspection(InspectionType.QUALITY);

        UUID mixedCase = insertLegacyDefect(inspectionId, 0, "'Critical'", "'Open'");
        UUID hyphenated = insertLegacyDefect(inspectionId, 1, "'major'", "'in-progress'");
        UUID spaced = insertLegacyDefect(inspectionId, 2, "'MINOR'", "'In Progress'");
        UUID underscored = insertLegacyDefect(inspectionId, 3, "'minor'", "'IN_PROGRESS'");
        UUID blank = insertLegacyDefect(inspectionId, 4, "'   '", "'  '");
        UUID unset = insertLegacyDefect(inspectionId, 5, "NULL", "NULL");

        run(BLANK_DEFECT_FIELDS);
        assertThat(unmappedCount(PROMOTE_SEVERITY)).isZero();
        assertThat(unmappedCount(PROMOTE_STATUS)).isZero();
        run(PROMOTE_SEVERITY);
        run(PROMOTE_STATUS);

        assertThat(readDefect(mixedCase)).containsExactly("CRITICAL", "OPEN");
        assertThat(readDefect(hyphenated)).containsExactly("MAJOR", "IN_PROGRESS");
        assertThat(readDefect(spaced)).containsExactly("MINOR", "IN_PROGRESS");
        assertThat(readDefect(underscored)).containsExactly("MINOR", "IN_PROGRESS");

        // A blank severity was never recorded, so it becomes null rather than a
        // guessed bucket; a blank status takes the OPEN default the entity applies.
        assertThat(readDefect(blank)).containsExactly(null, "OPEN");
        assertThat(readDefect(unset)).containsExactly(null, "OPEN");

        // No row was lost on the way through.
        assertThat(defectCount(inspectionId)).isEqualTo(6);

        // And the promoted values are what @Enumerated(STRING) reads back.
        entityManager.clear();
        InspectionDefect defect = entityManager.find(InspectionDefect.class, mixedCase);
        assertThat(defect.getSeverity()).isEqualTo(DefectSeverity.CRITICAL);
        assertThat(defect.getStatus()).isEqualTo(DefectStatus.OPEN);
    }

    @Test
    void defectPromotion_isIdempotent() {
        UUID inspectionId = insertLegacyInspection(InspectionType.QUALITY);
        UUID defectId = insertLegacyDefect(inspectionId, 0, "'major'", "'resolved'");

        run(BLANK_DEFECT_FIELDS);
        run(PROMOTE_SEVERITY);
        run(PROMOTE_STATUS);
        assertThat(readDefect(defectId)).containsExactly("MAJOR", "RESOLVED");

        run(PROMOTE_SEVERITY);
        run(PROMOTE_STATUS);
        assertThat(readDefect(defectId)).containsExactly("MAJOR", "RESOLVED");
    }

    @Test
    void unmappableValues_tripThePreconditionInsteadOfBeingRewritten() {
        UUID inspectionId = insertLegacyInspection(InspectionType.QUALITY);
        UUID defectId = insertLegacyDefect(inspectionId, 0, "'catastrophic'", "'wont-fix'");

        run(BLANK_DEFECT_FIELDS);

        // Both preconditions see the unknown value, so the changeset halts the
        // migration rather than dropping the row or coercing it into a bucket.
        assertThat(unmappedCount(PROMOTE_SEVERITY)).isEqualTo(1);
        assertThat(unmappedCount(PROMOTE_STATUS)).isEqualTo(1);

        // Were it to run anyway, the update leaves the unknown value untouched.
        run(PROMOTE_SEVERITY);
        run(PROMOTE_STATUS);
        assertThat(readDefect(defectId)).containsExactly("catastrophic", "wont-fix");
    }

    private void run(String changeSetId) {
        List<String> sql = statements.get(changeSetId);
        assertThat(sql).as("statements of changeset %s", changeSetId).isNotEmpty();
        for (String statement : sql) {
            entityManager.createNativeQuery(statement).executeUpdate();
        }
    }

    private long unmappedCount(String changeSetId) {
        String check = preconditions.get(changeSetId);
        assertThat(check).as("precondition of changeset %s", changeSetId).isNotNull();
        return ((Number) entityManager.createNativeQuery(check).getSingleResult()).longValue();
    }

    private UUID insertLegacyInspection(InspectionType type) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery(
                        "INSERT INTO inspections (id, inspection_number, title, type, scheduled_date) "
                                + "VALUES (CAST(:id AS UUID), :number, :title, :type, :scheduledDate)")
                .setParameter("id", id.toString())
                .setParameter("number", "INSP-" + id.toString().substring(0, 8))
                .setParameter("title", "Legacy " + type.getValue())
                .setParameter("type", type.name())
                .setParameter("scheduledDate", LocalDate.of(2026, 8, 20))
                .executeUpdate();
        return id;
    }

    /**
     * Inserts a defect with the severity and status written as SQL literals, so a
     * genuinely null column can be seeded alongside the free-text ones without
     * binding an untyped null parameter.
     */
    private UUID insertLegacyDefect(UUID inspectionId, int lineOrder, String severity, String status) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery(
                        "INSERT INTO inspection_defects (id, inspection_id, description, severity, "
                                + "corrective_action, status, line_order) "
                                + "VALUES (CAST(:id AS UUID), CAST(:inspectionId AS UUID), :description, "
                                + severity + ", :correctiveAction, " + status + ", :lineOrder)")
                .setParameter("id", id.toString())
                .setParameter("inspectionId", inspectionId.toString())
                .setParameter("description", "Legacy defect " + lineOrder)
                .setParameter("correctiveAction", "Rectify")
                .setParameter("lineOrder", lineOrder)
                .executeUpdate();
        return id;
    }

    private String readCategory(UUID inspectionId) {
        return (String) entityManager.createNativeQuery(
                        "SELECT category FROM inspections WHERE id = CAST(:id AS UUID)")
                .setParameter("id", inspectionId.toString())
                .getSingleResult();
    }

    private List<String> readDefect(UUID defectId) {
        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT severity, status FROM inspection_defects WHERE id = CAST(:id AS UUID)")
                .setParameter("id", defectId.toString())
                .getSingleResult();
        List<String> values = new ArrayList<>();
        values.add((String) row[0]);
        values.add((String) row[1]);
        return values;
    }

    private long defectCount(UUID inspectionId) {
        return ((Number) entityManager.createNativeQuery(
                        "SELECT count(*) FROM inspection_defects "
                                + "WHERE inspection_id = CAST(:id AS UUID)")
                .setParameter("id", inspectionId.toString())
                .getSingleResult()).longValue();
    }
}

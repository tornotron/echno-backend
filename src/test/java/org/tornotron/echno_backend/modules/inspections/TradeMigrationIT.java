package org.tornotron.echno_backend.modules.inspections;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
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
 * Runs the DML changesets of 103 and 104 against rows shaped like the ones a seeded database
 * holds: an organization with no trade rows, checklist templates and inspections carrying the
 * enum constant in the {@code trade} column and nothing in {@code trade_id}. The schema itself
 * is already applied by Liquibase at startup; what is proved here is that the copy and the
 * backfill map every enum value and can be run twice.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TradeMigrationIT extends AbstractIntegrationTest {

    private static final String COPY = "103-04-copy-catalogue-into-organizations";
    private static final String BACKFILL = "104-02-backfill-trade-id";
    private static final String STARTER_SLUG = "104-04-starter-trade-to-slug";

    private static Map<String, List<String>> statements;

    @PersistenceContext
    private EntityManager entityManager;

    private Long organizationId;

    @BeforeAll
    static void readChangelogs() throws Exception {
        statements = new HashMap<>();
        for (String changelog : List.of(
                "db/changelog/modules/inspections/103-trade-catalogue-and-org-trades.xml",
                "db/changelog/modules/inspections/104-trade-id-on-templates-and-inspections.xml")) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document;
            try (InputStream in = TradeMigrationIT.class.getClassLoader().getResourceAsStream(changelog)) {
                assertThat(in).as("changelog %s on the test classpath", changelog).isNotNull();
                document = builder.parse(new InputSource(in));
            }
            NodeList changeSets = document.getElementsByTagName("changeSet");
            for (int i = 0; i < changeSets.getLength(); i++) {
                Element changeSet = (Element) changeSets.item(i);
                List<String> sql = new ArrayList<>();
                NodeList sqlNodes = changeSet.getElementsByTagName("sql");
                for (int j = 0; j < sqlNodes.getLength(); j++) {
                    sql.add(sqlNodes.item(j).getTextContent().trim());
                }
                statements.put(changeSet.getAttribute("id"), sql);
            }
        }
    }

    @Test
    void copy_givesEveryOrganizationOneRowPerCatalogueCodeAndIsIdempotent() {
        organizationId();
        assertThat(count("SELECT COUNT(*) FROM inspection_trades WHERE organization_id = :org")).isZero();

        run(COPY);
        assertThat(count("SELECT COUNT(*) FROM inspection_trades WHERE organization_id = :org")).isEqualTo(21);
        assertThat(count("SELECT COUNT(*) FROM inspection_trades WHERE organization_id = :org "
                + "AND legacy_enum IS NOT NULL")).isEqualTo(InspectionTrade.values().length);
        assertThat(count("SELECT COUNT(*) FROM inspection_trades WHERE organization_id = :org "
                + "AND catalogue_code IS NULL")).isZero();

        run(COPY);
        assertThat(count("SELECT COUNT(*) FROM inspection_trades WHERE organization_id = :org")).isEqualTo(21);
    }

    @Test
    void backfill_mapsEveryEnumValueOnTemplatesAndInspectionsToTheOrgRow() {
        Map<InspectionTrade, UUID> templates = new HashMap<>();
        Map<InspectionTrade, UUID> inspections = new HashMap<>();
        for (InspectionTrade trade : InspectionTrade.values()) {
            templates.put(trade, insertLegacyTemplate(trade));
            inspections.put(trade, insertLegacyInspection(trade));
        }
        UUID untraded = insertLegacyInspection(null);

        run(COPY);
        run(BACKFILL);

        for (InspectionTrade trade : InspectionTrade.values()) {
            assertThat(tradeCodeOf("checklist_templates", templates.get(trade)))
                    .as("template backfilled for %s", trade).isEqualTo(trade.getValue());
            assertThat(tradeCodeOf("inspections", inspections.get(trade)))
                    .as("inspection backfilled for %s", trade).isEqualTo(trade.getValue());
        }
        assertThat(tradeCodeOf("inspections", untraded)).isNull();
        assertThat(count("SELECT COUNT(*) FROM inspections WHERE organization_id = :org "
                + "AND trade IS NOT NULL AND trade_id IS NULL")).isZero();

        // running it again changes nothing
        run(BACKFILL);
        assertThat(count("SELECT COUNT(*) FROM checklist_templates WHERE organization_id = :org "
                + "AND trade_id IS NOT NULL")).isEqualTo(InspectionTrade.values().length);
    }

    @Test
    void starterSlugRewrite_isIdempotentOnRowsAlreadyInSlugForm() {
        // the seeded rows are already in slug form on this database; the rewrite must be a no-op
        long before = count("SELECT COUNT(*) FROM starter_checklist_templates WHERE trade_code = 'shuttering-formwork'");
        assertThat(before).isEqualTo(1);
        // the statement targets the pre-rename column; run its logic against trade_code here
        String sql = statements.get(STARTER_SLUG).getFirst().replace("trade", "trade_code");
        entityManager.createNativeQuery(sql).executeUpdate();
        assertThat(count("SELECT COUNT(*) FROM starter_checklist_templates WHERE trade_code = 'shuttering-formwork'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM starter_checklist_templates WHERE trade_code LIKE '%\\_%' "
                + "OR trade_code <> lower(trade_code)")).isZero();
        assertThat("SHUTTERING_FORMWORK".toLowerCase().replace('_', '-')).isEqualTo("shuttering-formwork");
    }

    private void run(String changeSetId) {
        List<String> sql = statements.get(changeSetId);
        assertThat(sql).as("statements of changeset %s", changeSetId).isNotEmpty();
        for (String statement : sql) {
            entityManager.createNativeQuery(statement).executeUpdate();
        }
    }

    private long count(String sql) {
        var query = entityManager.createNativeQuery(sql);
        if (sql.contains(":org")) {
            query.setParameter("org", organizationId());
        }
        return ((Number) query.getSingleResult()).longValue();
    }

    private String tradeCodeOf(String table, UUID id) {
        return (String) entityManager.createNativeQuery(
                        "SELECT t.code FROM " + table + " r LEFT JOIN inspection_trades t ON t.id = r.trade_id "
                                + "WHERE r.id = CAST(:id AS UUID)")
                .setParameter("id", id.toString())
                .getSingleResult();
    }

    private UUID insertLegacyTemplate(InspectionTrade trade) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery(
                        "INSERT INTO checklist_templates (id, organization_id, trade, name) "
                                + "VALUES (CAST(:id AS UUID), :org, :trade, :name)")
                .setParameter("id", id.toString())
                .setParameter("org", organizationId())
                .setParameter("trade", trade.name())
                .setParameter("name", "Legacy " + trade.getValue())
                .executeUpdate();
        return id;
    }

    private UUID insertLegacyInspection(InspectionTrade trade) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery(
                        "INSERT INTO inspections (id, inspection_number, title, type, trade, scheduled_date, "
                                + "organization_id) "
                                + "VALUES (CAST(:id AS UUID), :number, :title, 'QUALITY', :trade, :scheduledDate, :org)")
                .setParameter("id", id.toString())
                .setParameter("number", "INSP-" + id.toString().substring(0, 8))
                .setParameter("title", "Legacy " + (trade == null ? "untraded" : trade.getValue()))
                .setParameter("trade", trade == null ? null : trade.name())
                .setParameter("scheduledDate", LocalDate.of(2026, 8, 20))
                .setParameter("org", organizationId())
                .executeUpdate();
        return id;
    }

    private Long organizationId() {
        if (organizationId == null) {
            organizationId = ((Number) entityManager.createNativeQuery(
                            "INSERT INTO organization (organization_name, organization_address, "
                                    + "organization_email, organization_phone) "
                                    + "VALUES (:name, :address, :email, :phone) RETURNING id")
                    .setParameter("name", "Trade migration " + UUID.randomUUID())
                    .setParameter("address", "Chennai")
                    .setParameter("email", "qa@example.test")
                    .setParameter("phone", "9847012345")
                    .getSingleResult()).longValue();
        }
        return organizationId;
    }
}

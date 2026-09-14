package org.tornotron.echno_backend.modules.inspections;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalogue reseed of changeset 119 (#814 item 6): the changesets re-run when their CSV
 * changes, every CSV code is in the table, and the insert they run adds a code the table
 * lacks without touching the rows it has. The statements are replayed here against a
 * staging table holding one new row, since the CSV itself cannot change under a test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CatalogueReseedIT extends AbstractIntegrationTest {

    private static final String CHANGELOG = "db/changelog/modules/inspections/119-catalogue-reseed.xml";
    private static final String TRADE_CSV = "db/seed/inspections/trade-catalogue.csv";
    private static final String ELEMENT_CSV = "db/seed/inspections/element-type-catalogue.csv";

    private static Map<String, Element> changeSets;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeAll
    static void readChangelog() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document;
        try (InputStream in = CatalogueReseedIT.class.getClassLoader().getResourceAsStream(CHANGELOG)) {
            assertThat(in).as("changelog on the test classpath").isNotNull();
            document = factory.newDocumentBuilder().parse(new InputSource(in));
        }
        changeSets = new HashMap<>();
        NodeList nodes = document.getElementsByTagName("changeSet");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element changeSet = (Element) nodes.item(i);
            changeSets.put(changeSet.getAttribute("id"), changeSet);
        }
    }

    @AfterEach
    void dropTheProbeRows() {
        entityManager.createNativeQuery("DELETE FROM trade_catalogue WHERE code = 'reseed-probe'").executeUpdate();
        entityManager.createNativeQuery("DROP TABLE IF EXISTS trade_catalogue_stage").executeUpdate();
    }

    @Test
    void theReseedChangesets_reRunWhenTheirCsvChanges() {
        for (String id : List.of("119-01-reseed-trade-catalogue", "119-02-reseed-element-type-catalogue")) {
            Element changeSet = changeSets.get(id);
            assertThat(changeSet).as("changeset %s", id).isNotNull();
            assertThat(changeSet.getAttribute("runOnChange")).as("%s is runOnChange", id).isEqualTo("true");
            assertThat(changeSet.getElementsByTagName("loadData").getLength()).as("%s loads its CSV", id).isEqualTo(1);
        }
    }

    @Test
    void everyCsvCode_isInTheCatalogue() throws Exception {
        for (String code : csvCodes(TRADE_CSV)) {
            assertThat(count("SELECT count(*) FROM trade_catalogue WHERE code = '" + code + "'"))
                    .as("trade %s", code).isEqualTo(1);
        }
        for (String code : csvCodes(ELEMENT_CSV)) {
            assertThat(count("SELECT count(*) FROM element_type_catalogue WHERE code = '" + code + "'"))
                    .as("element type %s", code).isEqualTo(1);
        }
    }

    @Test
    void theInsert_addsACodeTheTableLacksAndLeavesTheRowsItHasAlone() {
        List<String> sql = sqlOf("119-01-reseed-trade-catalogue");
        assertThat(sql).hasSize(4);
        String originalName = (String) entityManager.createNativeQuery(
                "SELECT name FROM trade_catalogue WHERE code = 'rcc'").getSingleResult();

        entityManager.createNativeQuery(sql.get(0)).executeUpdate();
        entityManager.createNativeQuery(sql.get(1)).executeUpdate();
        // what a later CSV would hold: one new code, and an existing code under another name
        entityManager.createNativeQuery("INSERT INTO trade_catalogue_stage (code, name, group_code, description, sort_order, active) "
                + "VALUES ('reseed-probe', 'Reseed probe', 'general', NULL, 999, true), "
                + "('rcc', 'Renamed in the CSV', 'structural', NULL, 40, true)").executeUpdate();
        entityManager.createNativeQuery(sql.get(2)).executeUpdate();
        entityManager.createNativeQuery(sql.get(3)).executeUpdate();

        assertThat(count("SELECT count(*) FROM trade_catalogue WHERE code = 'reseed-probe'")).isEqualTo(1);
        assertThat(entityManager.createNativeQuery("SELECT name FROM trade_catalogue WHERE code = 'rcc'").getSingleResult())
                .isEqualTo(originalName);
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'trade_catalogue_stage'")).isZero();
    }

    private static List<String> sqlOf(String changeSetId) {
        NodeList nodes = changeSets.get(changeSetId).getElementsByTagName("sql");
        List<String> sql = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            sql.add(nodes.item(i).getTextContent().trim());
        }
        return sql;
    }

    private static List<String> csvCodes(String resource) throws Exception {
        List<String> codes = new ArrayList<>();
        try (InputStream in = CatalogueReseedIT.class.getClassLoader().getResourceAsStream(resource);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            assertThat(line).startsWith("code,");
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    codes.add(line.substring(0, line.indexOf(',')));
                }
            }
        }
        assertThat(codes).isNotEmpty();
        return codes;
    }

    private long count(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }
}

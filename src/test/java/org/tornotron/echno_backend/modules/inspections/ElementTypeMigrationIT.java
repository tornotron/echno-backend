package org.tornotron.echno_backend.modules.inspections;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Changeset 105-04: the element type copy for existing organizations, run twice. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ElementTypeMigrationIT extends AbstractIntegrationTest {

    private static final String CHANGELOG =
            "db/changelog/modules/inspections/105-element-types-and-template-applicability.xml";
    private static final String COPY = "105-04-copy-element-types-into-organizations";

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void copy_givesEveryOrganizationOneRowPerCatalogueCodeAndIsIdempotent() throws Exception {
        long org = ((Number) entityManager.createNativeQuery(
                        "INSERT INTO organization (organization_name, organization_address, "
                                + "organization_email, organization_phone) "
                                + "VALUES (:name, 'Chennai', 'qa@example.test', '9847012345') RETURNING id")
                .setParameter("name", "Element type migration " + UUID.randomUUID())
                .getSingleResult()).longValue();
        String sql = copySql();

        entityManager.createNativeQuery(sql).executeUpdate();
        assertThat(count(org)).isEqualTo(17);
        entityManager.createNativeQuery(sql).executeUpdate();
        assertThat(count(org)).isEqualTo(17);
    }

    private long count(long org) {
        return ((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM org_element_types WHERE organization_id = :org AND catalogue_code IS NOT NULL")
                .setParameter("org", org)
                .getSingleResult()).longValue();
    }

    private static String copySql() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document;
        try (InputStream in = ElementTypeMigrationIT.class.getClassLoader().getResourceAsStream(CHANGELOG)) {
            assertThat(in).as("changelog %s on the test classpath", CHANGELOG).isNotNull();
            document = factory.newDocumentBuilder().parse(new InputSource(in));
        }
        NodeList changeSets = document.getElementsByTagName("changeSet");
        for (int i = 0; i < changeSets.getLength(); i++) {
            Element changeSet = (Element) changeSets.item(i);
            if (COPY.equals(changeSet.getAttribute("id"))) {
                return changeSet.getElementsByTagName("sql").item(0).getTextContent().trim();
            }
        }
        throw new AssertionError("changeset " + COPY + " not found");
    }
}

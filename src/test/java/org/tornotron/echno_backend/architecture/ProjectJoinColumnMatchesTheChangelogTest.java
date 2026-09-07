package org.tornotron.echno_backend.architecture;

import jakarta.persistence.JoinColumn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Holds the {@code project} association on the three procurement entities to what the changelog
 * actually creates.
 *
 * <p>Hibernate runs with {@code ddl-auto: validate}, and schema validation compares tables,
 * columns and types but not nullability. An entity may therefore declare
 * {@code @JoinColumn(nullable = false)} over a column the changelog left nullable and the
 * application will start without complaint, which is how the disagreement on
 * {@code Payable}, {@code PurchaseOrder} and {@code GoodsReceivedNote} survived. The database
 * wins at runtime, so a client reads a nullable column while the entity claims otherwise.
 *
 * <p>The changelog is the authority here, not the annotation, so the expectation is read out of
 * the changeset rather than written down twice. If a later changeset tightens one of these
 * columns, this test turns red and the entity can follow.
 */
class ProjectJoinColumnMatchesTheChangelogTest {

    private static final String COLUMN = "project_id";

    static Stream<Arguments> associations() {
        return Stream.of(
                Arguments.of(Payable.class, "payable",
                        "db/changelog/v4.0/baseline-005-level4-tables.xml"),
                Arguments.of(PurchaseOrder.class, "purchase_order",
                        "db/changelog/v4.0/baseline-003-level2-tables.xml"),
                Arguments.of(GoodsReceivedNote.class, "goods_received_note",
                        "db/changelog/v4.0/baseline-004-level3-tables.xml"));
    }

    @ParameterizedTest(name = "{1}.project_id")
    @MethodSource("associations")
    @DisplayName("the entity's join column agrees with the column the changelog creates")
    void joinColumnAgreesWithTheChangelog(Class<?> entity, String table, String changelog) {
        boolean columnIsNotNull = changelogDeclaresNotNull(changelog, table);
        JoinColumn joinColumn = projectJoinColumn(entity);

        assertThat(joinColumn.name())
                .as("the association is expected to map %s", COLUMN)
                .isEqualTo(COLUMN);
        assertThat(joinColumn.nullable())
                .as("%s.%s is %s in %s, so the entity must say the same",
                        table, COLUMN, columnIsNotNull ? "NOT NULL" : "nullable", changelog)
                .isEqualTo(!columnIsNotNull);
    }

    /** The {@code @JoinColumn} on the entity's {@code project} field. */
    private JoinColumn projectJoinColumn(Class<?> entity) {
        try {
            Field field = entity.getDeclaredField("project");
            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            assertThat(joinColumn)
                    .as("%s.project carries a @JoinColumn", entity.getSimpleName())
                    .isNotNull();
            return joinColumn;
        } catch (NoSuchFieldException e) {
            return fail("%s has no 'project' field".formatted(entity.getSimpleName()), e);
        }
    }

    /**
     * Whether the changeset that creates {@code table} constrains {@code project_id} to be
     * NOT NULL. Liquibase expresses that as a {@code <constraints nullable="false"/>} child of
     * the column, and its absence is what leaves the column nullable.
     */
    private boolean changelogDeclaresNotNull(String changelog, String table) {
        Element column = findColumn(changelog, table);
        NodeList children = column.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && "constraints".equals(element.getLocalName())) {
                return "false".equals(element.getAttribute("nullable"));
            }
        }
        return false;
    }

    /** The {@code project_id} column element inside the {@code createTable} for {@code table}. */
    private Element findColumn(String changelog, String table) {
        Document document = parse(changelog);
        NodeList tables = document.getElementsByTagNameNS("*", "createTable");
        for (int i = 0; i < tables.getLength(); i++) {
            Element createTable = (Element) tables.item(i);
            if (!table.equals(createTable.getAttribute("tableName"))) {
                continue;
            }
            NodeList columns = createTable.getElementsByTagNameNS("*", "column");
            for (int j = 0; j < columns.getLength(); j++) {
                Element column = (Element) columns.item(j);
                if (COLUMN.equals(column.getAttribute("name"))) {
                    return column;
                }
            }
            return fail("no %s column in the createTable for %s".formatted(COLUMN, table));
        }
        return fail("no createTable for %s in %s".formatted(table, changelog));
    }

    private Document parse(String changelog) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(changelog)) {
            assertThat(in).as("%s is on the classpath", changelog).isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().parse(in);
        } catch (Exception e) {
            return fail("could not read %s".formatted(changelog), e);
        }
    }
}

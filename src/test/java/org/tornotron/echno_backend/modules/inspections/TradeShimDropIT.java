package org.tornotron.echno_backend.modules.inspections;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema after changeset 117 (#770): the enum trade columns, the legacy_enum mirror and
 * the index on the enum column are gone, and a checklist template cannot exist without a
 * trade row. Liquibase applies the changelog at startup; this reads the catalog back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TradeShimDropIT extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void enumTradeColumns_areGone() {
        assertThat(columnNames("checklist_templates")).contains("trade_id").doesNotContain("trade");
        assertThat(columnNames("inspections")).contains("trade_id").doesNotContain("trade");
        assertThat(columnNames("inspection_trades")).contains("catalogue_code").doesNotContain("legacy_enum");
    }

    @Test
    void checklistTemplateTradeId_isRequired() {
        String nullable = (String) entityManager.createNativeQuery(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_name = 'checklist_templates' AND column_name = 'trade_id'")
                .getSingleResult();
        assertThat(nullable).isEqualTo("NO");
    }

    @Test
    void indexOnTheEnumColumn_isGoneAndTheTradeIdIndexStays() {
        List<String> indexes = indexNames("checklist_templates");
        assertThat(indexes).contains("idx_checklist_template_trade_id").doesNotContain("idx_checklist_template_trade");
    }

    @SuppressWarnings("unchecked")
    private List<String> columnNames(String table) {
        return entityManager.createNativeQuery(
                        "SELECT column_name FROM information_schema.columns WHERE table_name = :table")
                .setParameter("table", table)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<String> indexNames(String table) {
        return entityManager.createNativeQuery(
                        "SELECT index_name FROM information_schema.statistics WHERE table_name = :table")
                .setParameter("table", table)
                .getResultList();
    }
}

package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The assistant is dark until billing adds it: its feature row exists, and no plan carries it. The
 * modules before it grant theirs on every plan, so this is checked against one that does, to be
 * sure the question being asked of the data can actually find a grant.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AssistantFeatureSeedIT extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void theFeatureExistsAndNoPlanGrantsIt() {
        assertThat(count("SELECT count(*) FROM feature WHERE code = 'MODULE_ASSISTANT'")).isEqualTo(1);
        assertThat(grants("MODULE_ASSISTANT")).isZero();
    }

    @Test
    void aModuleThatShipsOnEveryPlanIsGrantedOnThem() {
        // The contrast that stops the test above passing for the wrong reason.
        assertThat(grants("MODULE_TOOLBOX_TALKS")).isPositive();
    }

    private long grants(String featureCode) {
        return count("SELECT count(*) FROM plan_feature pf JOIN feature f ON f.id = pf.feature_id "
                + "WHERE f.code = '" + featureCode + "'");
    }

    private long count(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }
}

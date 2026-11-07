package org.tornotron.echno_backend.modules.vision;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/** The seed on the real migration: the feature exists and no plan holds it. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VisionModuleSeedIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theFeatureIsSeededAndGrantedOnNoPlan() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feature WHERE code = 'MODULE_VISION' AND is_active",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM plan_feature pf JOIN feature f ON f.id = pf.feature_id
                WHERE f.code = 'MODULE_VISION'""", Long.class)).isZero();
    }
}

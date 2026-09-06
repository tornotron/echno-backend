package org.tornotron.echno_backend.compliance;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.compliance.repository.ComplianceRuleRepository;
import org.tornotron.echno_backend.project.enums.ProjectType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the curated Kerala and Tamil Nadu compliance catalogue as changelog 089 seeds it,
 * against a real CockroachDB (see {@link AbstractIntegrationTest}).
 *
 * <p>Reference data is worth asserting for the same reason schema is. The rules here are
 * statutory citations transcribed from a legal specification, they are the only input the AI
 * generation call reasons over, and a row that silently failed to land shows up as a
 * compliance the project was never told to obtain. That is the failure this catches.
 *
 * <p>What the tests are actually checking, in the order it matters: that the catalogue a
 * project sees is the transcribed one and not the placeholder set it replaced, that no rule in
 * it is missing, and that the seed can be applied twice without doubling anything.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ComplianceRuleSeedIT extends AbstractIntegrationTest {

    /** The twelve national rules, stored once per pilot jurisdiction. */
    private static final List<String> NATIONAL_CODES = List.of(
            "NAT-01", "NAT-02", "NAT-03", "NAT-04", "NAT-05", "NAT-06",
            "NAT-07", "NAT-08", "NAT-09", "NAT-10", "NAT-11", "NAT-12");

    private static final List<String> KERALA_CODES = List.of(
            "KL-01", "KL-02", "KL-03", "KL-04", "KL-05", "KL-06", "KL-07", "KL-08", "KL-09");

    private static final List<String> TAMIL_NADU_CODES = List.of(
            "TN-01", "TN-02", "TN-03", "TN-04", "TN-05", "TN-06", "TN-07", "TN-08", "TN-09");

    /** The 037 placeholders the transcribed catalogue supersedes. */
    private static final List<String> RETIRED_CODES = List.of(
            "KL-BPA", "KL-ENV-CLEAR", "KL-FIRE-NOC", "KL-LABOUR-LIC", "KL-OCC-CERT",
            "TN-BPA", "TN-ENV-CLEAR", "TN-FIRE-NOC", "TN-LABOUR-LIC", "TN-OCC-CERT");

    @Autowired
    private ComplianceRuleRepository rules;

    @Autowired
    private EntityManager em;

    @Test
    void everyPilotJurisdiction_carriesTheTwelveNationalAndNineStateRules() {
        assertJurisdiction("Kerala", ProjectType.RESIDENTIAL, KERALA_CODES);
        assertJurisdiction("Kerala", ProjectType.COMMERCIAL, KERALA_CODES);
        assertJurisdiction("Tamil Nadu", ProjectType.RESIDENTIAL, TAMIL_NADU_CODES);
        assertJurisdiction("Tamil Nadu", ProjectType.COMMERCIAL, TAMIL_NADU_CODES);
    }

    @Test
    void keralaRulesDoNotLeakIntoTamilNadu_andTheReverse() {
        assertThat(activeCodes("Tamil Nadu", ProjectType.RESIDENTIAL))
                .doesNotContainAnyElementsOf(KERALA_CODES);
        assertThat(activeCodes("Kerala", ProjectType.RESIDENTIAL))
                .doesNotContainAnyElementsOf(TAMIL_NADU_CODES);
    }

    @Test
    void supersededPlaceholders_areNoLongerCandidates() {
        assertThat(activeCodes("Kerala", ProjectType.RESIDENTIAL))
                .doesNotContainAnyElementsOf(RETIRED_CODES);
        assertThat(activeCodes("Tamil Nadu", ProjectType.RESIDENTIAL))
                .doesNotContainAnyElementsOf(RETIRED_CODES);

        // Retired, not deleted: the rows survive so generated inspections still resolve the
        // code they were stamped with.
        assertThat(codeCount("KL-BPA")).isPositive();
        assertThat(codeCount("TN-BPA")).isPositive();
    }

    @Test
    void structuralStabilityPlaceholder_staysActive_becauseNothingSupersedesIt() {
        // The specification does not cover a structural stability certificate, so retiring it
        // with the rest would narrow what a project is told to obtain. Deliberate; see 089.
        assertThat(activeCodes("Kerala", ProjectType.RESIDENTIAL)).contains("KL-STRUCT-STAB");
        assertThat(activeCodes("Tamil Nadu", ProjectType.RESIDENTIAL)).contains("TN-STRUCT-STAB");
    }

    @Test
    void maharashtraCatalogue_isUntouched() {
        // Outside the pilot and the only catalogue those projects have. Generation rejects a
        // jurisdiction with no candidate rules outright, so emptying it would break it.
        assertThat(activeCodes("Maharashtra", ProjectType.RESIDENTIAL)).isNotEmpty();
    }

    @Test
    void everySeededRule_carriesTheFieldsTheGenerationCallReads() {
        List<ComplianceRule> seeded = rules
                .findByStateIgnoreCaseAndProjectTypeAndActiveTrue("Kerala", ProjectType.RESIDENTIAL)
                .stream()
                .filter(r -> r.getCode().startsWith("NAT-") || r.getCode().startsWith("KL-0"))
                .toList();

        assertThat(seeded).hasSize(NATIONAL_CODES.size() + KERALA_CODES.size());
        assertThat(seeded).allSatisfy(rule -> {
            assertThat(rule.getName()).isNotBlank();
            assertThat(rule.getAuthority()).isNotBlank();
            assertThat(rule.getPhase()).isNotNull();
            assertThat(rule.getDefaultRiskLevel()).isNotNull();
            assertThat(rule.getResolutionOptions()).isNotBlank();
            assertThat(rule.getEffectiveFrom()).isNotNull();
            // The description is where the governing law, applicability, evidence, validity and
            // source all live, because the table has no column for any of them. A rule without
            // one reaches the model as a bare name.
            assertThat(rule.getDescription()).isNotBlank();
            assertThat(rule.getDescription()).contains("Source:");
        });
    }

    @Test
    void reapplyingTheSeed_insertsNothing() {
        long before = totalRules();

        // The same statement the changelog runs. It is the ON CONFLICT that makes 089 safe to
        // apply to an environment already holding some of these rows.
        em.createNativeQuery("""
                INSERT INTO compliance_rules (state, project_type, phase, code, name,
                                              description, default_risk_level,
                                              resolution_options, authority, active,
                                              created_at, updated_at, effective_from)
                SELECT 'Kerala', 'RESIDENTIAL', 'PRE_CONSTRUCTION', 'KL-01', 'Building permit',
                       'reapplied', 'CRITICAL', 'x', 'y', true,
                       current_timestamp, current_timestamp, current_timestamp
                ON CONFLICT (state, project_type, code) DO NOTHING
                """).executeUpdate();

        assertThat(totalRules()).isEqualTo(before);
    }

    private void assertJurisdiction(String state, ProjectType type, List<String> stateCodes) {
        List<String> codes = activeCodes(state, type);
        assertThat(codes)
                .as("%s / %s national rules", state, type)
                .containsAll(NATIONAL_CODES);
        assertThat(codes)
                .as("%s / %s state rules", state, type)
                .containsAll(stateCodes);
    }

    private List<String> activeCodes(String state, ProjectType type) {
        return rules.findByStateIgnoreCaseAndProjectTypeAndActiveTrue(state, type)
                .stream()
                .map(ComplianceRule::getCode)
                .toList();
    }

    private long codeCount(String code) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM compliance_rules WHERE code = :code")
                .setParameter("code", code)
                .getSingleResult()).longValue();
    }

    private long totalRules() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM compliance_rules")
                .getSingleResult()).longValue();
    }
}

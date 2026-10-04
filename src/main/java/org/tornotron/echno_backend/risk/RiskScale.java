package org.tornotron.echno_backend.risk;

import java.util.List;
import java.util.Map;

/**
 * The fixed vocabularies of a risk, as the codes the web client stores, and the score they give.
 *
 * <p>Probability and impact each map to 1 to 5, and a score is their product (1 to 25). The
 * category codes are the construction risk categories the product team supplied (ClickUp
 * 86d4609hd) plus the seven from the generic list they replaced, which risks recorded before the
 * change still carry. Sub-categories are free text and are not listed here.
 */
public final class RiskScale {

    public static final String PROBABILITY_PATTERN = "very-low|low|medium|high|very-high";
    public static final String IMPACT_PATTERN = "negligible|minor|moderate|major|catastrophic";
    public static final String STATUS_PATTERN =
            "identified|analysed|response-planned|mitigated|closed|occurred";
    public static final String RESPONSE_PATTERN = "avoid|mitigate|transfer|accept";
    public static final String CATEGORY_PATTERN = "design-engineering|contractual-legal|financial-commercial"
            + "|procurement-supply-chain|construction-execution|site-ground-conditions"
            + "|health-safety-security|environmental|quality|resource-manpower|plant-equipment"
            + "|schedule-planning|client-stakeholder|statutory-regulatory|external-force-majeure"
            + "|subcontractor|technology-data-information|commissioning-handover|reputational-business"
            + "|schedule|cost|scope|safety|technical|external|resource";

    private static final List<String> PROBABILITIES = List.of(PROBABILITY_PATTERN.split("\\|"));
    private static final List<String> IMPACTS = List.of(IMPACT_PATTERN.split("\\|"));

    private static final Map<String, Integer> PROBABILITY_SCORE = scores(PROBABILITIES);
    private static final Map<String, Integer> IMPACT_SCORE = scores(IMPACTS);

    private RiskScale() {
    }

    /**
     * The score of a probability and an impact.
     *
     * @throws IllegalArgumentException if either is not one of the codes, which request
     *     validation rules out before this is reached.
     */
    public static int score(String probability, String impact) {
        Integer p = PROBABILITY_SCORE.get(probability);
        Integer i = IMPACT_SCORE.get(impact);
        if (p == null || i == null) {
            throw new IllegalArgumentException("Unknown probability or impact: " + probability + ", " + impact);
        }
        return p * i;
    }

    private static Map<String, Integer> scores(List<String> codes) {
        java.util.HashMap<String, Integer> map = new java.util.HashMap<>();
        for (int k = 0; k < codes.size(); k++) {
            map.put(codes.get(k), k + 1);
        }
        return Map.copyOf(map);
    }
}

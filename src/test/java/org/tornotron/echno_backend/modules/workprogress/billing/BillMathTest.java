package org.tornotron.echno_backend.modules.workprogress.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentEffect;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentSource;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionBasis;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionKind;
import org.tornotron.echno_backend.modules.workprogress.billing.service.BillMath;

/** The bill arithmetic on its own: where it rounds, what a cap does, and what each rule runs on. */
class BillMathTest {

    @Test
    void aLineIsRoundedOnceToPaiseHalfUp() {
        assertThat(BillMath.lineAmount(new BigDecimal("120.255"), new BigDecimal("8500.50")))
                .isEqualByComparingTo("1022227.63");
        assertThat(BillMath.lineAmount(new BigDecimal("0.001"), new BigDecimal("5.00"))).isEqualByComparingTo("0.01");
        assertThat(BillMath.lineAmount(new BigDecimal("0.001"), new BigDecimal("4.99"))).isEqualByComparingTo("0.00");
        assertThat(BillMath.lineAmount(new BigDecimal("2"), new BigDecimal("0.125")).scale()).isEqualTo(2);
    }

    @Test
    void aPercentIsTheExactProductRoundedOnce() {
        assertThat(BillMath.percentOf(new BigDecimal("1973155.13"), new BigDecimal("5.000"))).isEqualByComparingTo("98657.76");
        assertThat(BillMath.percentOf(new BigDecimal("1973155.13"), new BigDecimal("18"))).isEqualByComparingTo("355167.92");
        assertThat(BillMath.percentOf(new BigDecimal("0.10"), new BigDecimal("5"))).isEqualByComparingTo("0.01");
    }

    @Test
    void aMilestoneIsWorthItsAmountElseItsShareOfTheContract() {
        assertThat(BillMath.milestoneValue(new BigDecimal("5000000"), new BigDecimal("100000000"), new BigDecimal("15")))
                .isEqualByComparingTo("5000000.00");
        assertThat(BillMath.milestoneValue(null, new BigDecimal("100000000"), new BigDecimal("15")))
                .isEqualByComparingTo("15000000.00");
        assertThat(BillMath.milestoneValue(BigDecimal.ZERO, null, new BigDecimal("15"))).isNull();
    }

    @Test
    void aCapTakesOnlyWhatIsLeftAndNeverLessThanNothing() {
        BillMath.Rule advance = rule(DeductionKind.ADVANCE_RECOVERY, DeductionBasis.FIXED, null, "10000", "15000", "10000");
        assertThat(BillMath.ruleAmount(advance, new BigDecimal("999999"))).isEqualByComparingTo("5000.00");
        BillMath.Rule spent = rule(DeductionKind.ADVANCE_RECOVERY, DeductionBasis.FIXED, null, "10000", "15000", "16000");
        assertThat(BillMath.ruleAmount(spent, new BigDecimal("999999"))).isEqualByComparingTo("0.00");
        BillMath.Rule retention = rule(DeductionKind.RETENTION, DeductionBasis.PERCENT, "5", null, "100", "40");
        assertThat(BillMath.ruleAmount(retention, new BigDecimal("10000"))).isEqualByComparingTo("60.00");
    }

    @Test
    void everyRuleRunsOnGrossPlusManualAdditionsAndTheNetAddsAndDeductsTheLot() {
        BigDecimal gross = new BigDecimal("1948155.13");
        List<BillMath.Adjustment> manual = List.of(new BillMath.Adjustment(null, DeductionKind.VARIATION, "VO-1",
                AdjustmentEffect.ADD, DeductionBasis.FIXED, null, new BigDecimal("25000.00"), AdjustmentSource.MANUAL));
        BigDecimal base = BillMath.base(gross, manual);
        assertThat(base).isEqualByComparingTo("1973155.13");

        List<BillMath.Adjustment> ruleLines = BillMath.applyRules(List.of(
                rule(DeductionKind.RETENTION, DeductionBasis.PERCENT, "5", null, null, null),
                rule(DeductionKind.GST, DeductionBasis.PERCENT, "18", null, null, null),
                rule(DeductionKind.TDS, DeductionBasis.PERCENT, "1", null, null, null),
                rule(DeductionKind.ADVANCE_RECOVERY, DeductionBasis.FIXED, null, "10000", "15000", null)), base);
        assertThat(ruleLines).extracting(BillMath.Adjustment::amount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("98657.76"), new BigDecimal("355167.92"), new BigDecimal("19731.55"),
                        new BigDecimal("10000.00"));

        List<BillMath.Adjustment> all = new java.util.ArrayList<>(ruleLines);
        all.addAll(manual);
        BillMath.Totals totals = BillMath.totals(gross, all);
        assertThat(totals.additions()).isEqualByComparingTo("380167.92");
        assertThat(totals.deductions()).isEqualByComparingTo("128389.31");
        assertThat(totals.net()).isEqualByComparingTo("2199933.74");
        assertThat(totals.base()).isEqualByComparingTo("1973155.13");
    }

    @Test
    void aRuleThatComesToNothingLeavesNoLine() {
        assertThat(BillMath.applyRules(List.of(rule(DeductionKind.TDS, DeductionBasis.PERCENT, "1", null, null, null)),
                BigDecimal.ZERO)).isEmpty();
    }

    private static BillMath.Rule rule(DeductionKind kind, DeductionBasis basis, String rate, String fixed, String cap,
                                      String before) {
        return new BillMath.Rule(UUID.randomUUID(), kind, kind.name(), kind.defaultEffect(), basis,
                rate == null ? null : new BigDecimal(rate), fixed == null ? null : new BigDecimal(fixed),
                cap == null ? null : new BigDecimal(cap), before == null ? null : new BigDecimal(before));
    }
}

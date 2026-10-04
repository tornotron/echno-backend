package org.tornotron.echno_backend.modules.workprogress.billing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentEffect;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentSource;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionBasis;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.DeductionKind;

/**
 * The arithmetic of a bill, in one place and free of persistence so it can be tested on its own.
 *
 * <p>Money is rupees to two places and quantities are to three, each rounded half up at the step
 * named, never earlier: a line amount is the exact product of quantity and rate rounded once, a
 * percent is the exact product of base and rate divided by a hundred and rounded once, and every
 * total is a sum of already rounded amounts, so the figures on a printed bill add up.
 *
 * <p>The adjustment base is the gross certified amount plus the manual additions (variations,
 * extra items, escalation). Every percent rule runs on that base, so GST and TDS are worked out on
 * the value of the work and never on each other. The net payable is the gross plus every addition
 * less every deduction.
 */
public final class BillMath {

    public static final int MONEY_SCALE = 2;
    public static final int QUANTITY_SCALE = 3;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private BillMath() {
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal quantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    /** Quantity times rate, rounded to paise once. */
    public static BigDecimal lineAmount(BigDecimal quantity, BigDecimal rate) {
        if (quantity == null || rate == null) {
            return zero();
        }
        return money(quantity.multiply(rate));
    }

    /** {@code percent} percent of {@code base}, rounded to paise once. */
    public static BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
        if (base == null || percent == null) {
            return zero();
        }
        return money(base.multiply(percent).divide(HUNDRED));
    }

    /**
     * The value of a contract milestone: its own amount when one is recorded, otherwise its payment
     * percentage of the contract value; null when neither can be worked out.
     */
    public static BigDecimal milestoneValue(BigDecimal amount, BigDecimal contractValue, BigDecimal paymentPercent) {
        if (amount != null && amount.signum() > 0) {
            return money(amount);
        }
        if (contractValue != null && paymentPercent != null && contractValue.signum() > 0 && paymentPercent.signum() > 0) {
            return percentOf(contractValue, paymentPercent);
        }
        return null;
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }

    /** A contract rule as it stands when a bill is worked out, with what it has already taken. */
    public record Rule(UUID ruleId, DeductionKind kind, String label, AdjustmentEffect effect, DeductionBasis basis,
                       BigDecimal rate, BigDecimal fixedAmount, BigDecimal capAmount, BigDecimal appliedBefore) {
    }

    /** One adjustment line, from a rule or entered by hand. */
    public record Adjustment(UUID ruleId, DeductionKind kind, String label, AdjustmentEffect effect,
                             DeductionBasis basis, BigDecimal rate, BigDecimal amount, AdjustmentSource source) {
    }

    /** The totals of a bill. */
    public record Totals(BigDecimal gross, BigDecimal base, BigDecimal additions, BigDecimal deductions,
                         BigDecimal net) {
    }

    /**
     * What one rule takes from (or adds to) a bill with this base. A cap limits the rule's total
     * over the contract, so a capped rule takes only what is left of its cap and never less than
     * nothing.
     */
    public static BigDecimal ruleAmount(Rule rule, BigDecimal base) {
        BigDecimal amount = rule.basis() == DeductionBasis.PERCENT
                ? percentOf(base, rule.rate())
                : money(rule.fixedAmount() == null ? BigDecimal.ZERO : rule.fixedAmount());
        if (rule.capAmount() != null) {
            BigDecimal before = rule.appliedBefore() == null ? BigDecimal.ZERO : rule.appliedBefore();
            BigDecimal left = money(rule.capAmount().subtract(before).max(BigDecimal.ZERO));
            amount = amount.min(left);
        }
        return amount.max(zero());
    }

    /** The adjustment base: gross plus the manual additions. */
    public static BigDecimal base(BigDecimal gross, List<Adjustment> manual) {
        BigDecimal base = money(gross);
        for (Adjustment adjustment : manual) {
            if (adjustment.effect() == AdjustmentEffect.ADD) {
                base = base.add(adjustment.amount());
            }
        }
        return base;
    }

    /** The rule lines for a bill, in the rules' order, skipping a rule that comes to nothing. */
    public static List<Adjustment> applyRules(List<Rule> rules, BigDecimal base) {
        return rules.stream()
                .map(rule -> new Adjustment(rule.ruleId(), rule.kind(), rule.label(), rule.effect(), rule.basis(),
                        rule.basis() == DeductionBasis.PERCENT ? rule.rate() : null, ruleAmount(rule, base),
                        AdjustmentSource.RULE))
                .filter(adjustment -> adjustment.amount().signum() > 0)
                .toList();
    }

    /** Gross, base, additions, deductions and net for a bill with these adjustment lines. */
    public static Totals totals(BigDecimal gross, List<Adjustment> adjustments) {
        BigDecimal g = money(gross);
        BigDecimal additions = zero();
        BigDecimal deductions = zero();
        BigDecimal manualAdditions = zero();
        for (Adjustment adjustment : adjustments) {
            if (adjustment.effect() == AdjustmentEffect.ADD) {
                additions = additions.add(adjustment.amount());
                if (adjustment.source() == AdjustmentSource.MANUAL) {
                    manualAdditions = manualAdditions.add(adjustment.amount());
                }
            } else {
                deductions = deductions.add(adjustment.amount());
            }
        }
        return new Totals(g, g.add(manualAdditions), additions, deductions, g.add(additions).subtract(deductions));
    }
}

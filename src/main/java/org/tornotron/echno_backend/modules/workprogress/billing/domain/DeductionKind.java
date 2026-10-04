package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/**
 * What a commercial adjustment is. GST adds to the bill by default; every other kind deducts.
 */
public enum DeductionKind {
    RETENTION,
    ADVANCE_RECOVERY,
    PENALTY_LD,
    TDS,
    GST,
    VARIATION,
    EXTRA_ITEM,
    ESCALATION,
    OTHER;

    /** The effect a rule of this kind has when none is stated. */
    public AdjustmentEffect defaultEffect() {
        return switch (this) {
            case GST, VARIATION, EXTRA_ITEM, ESCALATION -> AdjustmentEffect.ADD;
            default -> AdjustmentEffect.DEDUCT;
        };
    }
}

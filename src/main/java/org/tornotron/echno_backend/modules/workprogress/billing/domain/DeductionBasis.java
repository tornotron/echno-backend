package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/**
 * How an adjustment's amount is worked out: a percent of the bill's base, or a fixed amount.
 */
public enum DeductionBasis {
    PERCENT,
    FIXED
}

package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/**
 * Where an adjustment line on a bill came from: a contract's rule at certification, or entered by hand.
 */
public enum AdjustmentSource {
    RULE,
    MANUAL
}

package org.tornotron.echno_backend.modules.documentingestion.domain;

/**
 * A row's status in the Tender Compliance Matrix (design section 4.7). {@code UNKNOWN} whenever the
 * company profile field is blank or a value cannot be read, so a missing figure never shows as a gap.
 */
public enum MatrixStatus {
    MATCH,
    GAP,
    UNKNOWN
}

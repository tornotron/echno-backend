package org.tornotron.echno_backend.modules.documentingestion.domain;

/**
 * A check a reader can understand that failed on a candidate: arithmetic that does not add up, a
 * quote not found on its page, an OCR page, a date out of order. A flagged candidate is accepted
 * only when each flag is acknowledged by its code.
 *
 * @param code Stable code, such as {@code AMOUNT_MISMATCH}
 * @param message One plain sentence for the review screen
 */
public record CandidateFlag(String code, String message) {
}

package org.tornotron.echno_backend.modules.documentingestion.domain;

/**
 * Something about a BOQ sheet as a whole that a reviewer should read, such as a section total that
 * does not match its items.
 *
 * @param code Stable code, such as {@code SECTION_TOTAL_MISMATCH}
 * @param message One plain sentence for the review screen
 * @param row The sheet row it is about, or null
 */
public record BoqWarning(String code, String message, Integer row) {
}

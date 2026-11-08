package org.tornotron.echno_backend.modules.documentingestion.domain;

/**
 * A page that was not read, and why, for the banner on the review screen.
 *
 * @param page   Physical page number, from 1
 * @param reason {@link #NOT_ENGLISH}, {@link #NOT_PARSED} or {@link #READ_FAILED}
 * @param detail One plain sentence, or null
 */
public record UnreadPage(int page, String reason, String detail) {

    /** The page's text is not English; this version reads English only, so it was not sent. */
    public static final String NOT_ENGLISH = "NOT_ENGLISH";
    /** The worker could not read the page. */
    public static final String NOT_PARSED = "NOT_PARSED";
    /** The page was sent and no usable answer came back after the retries. */
    public static final String READ_FAILED = "READ_FAILED";
}

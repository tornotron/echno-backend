package org.tornotron.echno_backend.indent.enums;

/**
 * Where an indent has got to.
 *
 * <p>Each constant carries the words a person should see. The constant name is the wire format
 * and the storage format, and it stays that way: {@link #getLabel()} exists for documents the
 * client reads, where "ON_SITE" is the internal name leaking onto a page rather than a status.
 * Nothing serializes the label, so the API and the database are unaffected.
 */
public enum IndentStatus {
    ON_SITE("On site"),
    DELAYED("Delayed"),
    PENDING("Pending"),
    ORDERED("Ordered"),
    CANCELLED("Cancelled");

    private final String label;

    IndentStatus(String label) {
        this.label = label;
    }

    /** The status as it should be printed for a reader, for example "On site". */
    public String getLabel() {
        return label;
    }
}

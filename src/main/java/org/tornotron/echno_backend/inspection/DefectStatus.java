package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Resolution state of a defect raised during an inspection, defaulting to
 * {@link #OPEN} exactly as the free-text column it replaces did. The wire values
 * are unchanged from the string union the web contract already documents
 * ('open' | 'in-progress' | 'resolved' | 'verified'); the constant name is the
 * database representation stored by {@code @Enumerated(STRING)}.
 */
public enum DefectStatus {
    OPEN("open"),
    IN_PROGRESS("in-progress"),
    RESOLVED("resolved"),
    VERIFIED("verified");

    private final String value;

    DefectStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DefectStatus fromValue(String value) {
        for (DefectStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown defect status: " + value);
    }
}

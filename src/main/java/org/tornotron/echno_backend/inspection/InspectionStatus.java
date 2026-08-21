package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle state of an inspection. The wire value is the hyphenated form from
 * {@link #getValue()}; the constant name is the database representation stored
 * by {@code @Enumerated(STRING)}.
 */
public enum InspectionStatus {
    SCHEDULED("scheduled"),
    IN_PROGRESS("in-progress"),
    COMPLETED("completed"),
    FAILED("failed"),
    PASSED("passed"),
    PASSED_WITH_REMARKS("passed-with-remarks"),
    CANCELLED("cancelled"),
    SUGGESTED("suggested");

    private final String value;

    InspectionStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static InspectionStatus fromValue(String value) {
        for (InspectionStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown inspection status: " + value);
    }
}

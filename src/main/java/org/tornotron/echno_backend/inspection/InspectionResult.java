package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Outcome of a completed inspection. Nullable on the entity until the inspection
 * is concluded. The wire value is the hyphenated form from {@link #getValue()};
 * the constant name is the database representation.
 */
public enum InspectionResult {
    PASSED("passed"),
    FAILED("failed"),
    PASSED_WITH_REMARKS("passed-with-remarks"),
    PENDING("pending");

    private final String value;

    InspectionResult(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static InspectionResult fromValue(String value) {
        for (InspectionResult result : values()) {
            if (result.value.equalsIgnoreCase(value)) {
                return result;
            }
        }
        throw new IllegalArgumentException("Unknown inspection result: " + value);
    }
}

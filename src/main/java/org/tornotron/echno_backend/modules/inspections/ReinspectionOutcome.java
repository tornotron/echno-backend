package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What a reinspection attempt found: nothing yet, the work now conforms, or it still does not. */
public enum ReinspectionOutcome {
    PENDING("pending"),
    PASSED("passed"),
    FAILED("failed");

    private final String value;

    ReinspectionOutcome(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ReinspectionOutcome fromValue(String value) {
        for (ReinspectionOutcome outcome : values()) {
            if (outcome.value.equalsIgnoreCase(value)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("Unknown reinspection outcome: " + value);
    }
}

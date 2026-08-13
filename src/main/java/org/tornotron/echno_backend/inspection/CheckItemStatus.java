package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Per-check-point outcome within an inspection. The wire value is the hyphenated
 * form from {@link #getValue()}; the constant name is the database representation.
 * The passed and failed counts on an inspection are derived from these statuses.
 */
public enum CheckItemStatus {
    PASSED("passed"),
    FAILED("failed"),
    NOT_APPLICABLE("not-applicable"),
    PENDING("pending");

    private final String value;

    CheckItemStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static CheckItemStatus fromValue(String value) {
        for (CheckItemStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown check item status: " + value);
    }
}

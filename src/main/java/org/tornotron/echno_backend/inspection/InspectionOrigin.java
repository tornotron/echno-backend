package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How an inspection came to exist: entered by a user ({@code MANUAL}) or produced
 * by the compliance generation flow ({@code AI_GENERATED}). Defaults to
 * {@code MANUAL} on the entity. The wire value is the hyphenated form from
 * {@link #getValue()}; the constant name is the database representation stored by
 * {@code @Enumerated(STRING)}.
 */
public enum InspectionOrigin {
    MANUAL("manual"),
    AI_GENERATED("ai-generated");

    private final String value;

    InspectionOrigin(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static InspectionOrigin fromValue(String value) {
        for (InspectionOrigin origin : values()) {
            if (origin.value.equalsIgnoreCase(value)) {
                return origin;
            }
        }
        throw new IllegalArgumentException("Unknown inspection origin: " + value);
    }
}

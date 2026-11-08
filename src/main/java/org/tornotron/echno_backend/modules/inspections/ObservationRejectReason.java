package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Why a reviewer rejected an observation. Only FALSE_POSITIVE marks the rejected regions as
 * negative training examples; the other reasons keep them out of the training signal.
 * The constant name is the database representation stored by {@code @Enumerated(STRING)};
 * the slug is the wire value.
 */
public enum ObservationRejectReason {
    FALSE_POSITIVE("false-positive"), DUPLICATE("duplicate"), OUT_OF_SCOPE("out-of-scope"),
    POOR_IMAGE("poor-image");

    private final String value;

    ObservationRejectReason(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ObservationRejectReason fromValue(String value) {
        for (ObservationRejectReason candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown ObservationRejectReason: " + value);
    }
}

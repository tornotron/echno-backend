package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The decision a reviewer takes on a pending observation.
 * The constant name is the database representation stored by {@code @Enumerated(STRING)};
 * the slug is the wire value.
 */
public enum ObservationReviewDecision {
    ACCEPT("accept"), REJECT("reject"), MODIFY("modify");

    private final String value;

    ObservationReviewDecision(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ObservationReviewDecision fromValue(String value) {
        for (ObservationReviewDecision candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown ObservationReviewDecision: " + value);
    }
}

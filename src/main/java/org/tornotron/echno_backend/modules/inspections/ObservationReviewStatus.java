package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where an observation stands with its human reviewer. Every machine intake lands PENDING; a person moves it to one of the three decisions exactly once.
 * The constant name is the database representation stored by {@code @Enumerated(STRING)};
 * the slug is the wire value.
 */
public enum ObservationReviewStatus {
    PENDING("pending"), ACCEPTED("accepted"), REJECTED("rejected"), MODIFIED("modified");

    private final String value;

    ObservationReviewStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ObservationReviewStatus fromValue(String value) {
        for (ObservationReviewStatus candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown ObservationReviewStatus: " + value);
    }
}

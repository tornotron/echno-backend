package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Who or what produced an observation. The source is metadata: every source goes through the same review, evidence and outcome path, and only the metadata columns filled on the row differ.
 * The constant name is the database representation stored by {@code @Enumerated(STRING)};
 * the slug is the wire value.
 */
public enum ObservationSource {
    HUMAN("human"), AI("ai"), DRONE("drone"), ROBOT("robot"), FIXED_CAMERA("fixed-camera");

    private final String value;

    ObservationSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ObservationSource fromValue(String value) {
        for (ObservationSource candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown ObservationSource: " + value);
    }
}

package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Who drew an observation region: the producer that posted the observation, a reviewer
 * correcting it during a MODIFY, or the person who recorded a human observation.
 * The constant name is the database representation stored by {@code @Enumerated(STRING)};
 * the slug is the wire value.
 */
public enum RegionOrigin {
    PRODUCER("producer"), REVIEWER("reviewer"), HUMAN("human");

    private final String value;

    RegionOrigin(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RegionOrigin fromValue(String value) {
        for (RegionOrigin candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown RegionOrigin: " + value);
    }
}

package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The geometry of an observation region. A polygon also stores its bounding box, so filtering,
 * cropping and export read the same four columns for both shapes.
 * The constant name is the database representation stored by {@code @Enumerated(STRING)};
 * the slug is the wire value.
 */
public enum RegionShape {
    BOX("box"), POLYGON("polygon");

    private final String value;

    RegionShape(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RegionShape fromValue(String value) {
        for (RegionShape candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown RegionShape: " + value);
    }
}

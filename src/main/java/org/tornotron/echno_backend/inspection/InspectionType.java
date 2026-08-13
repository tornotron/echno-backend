package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The kind of site inspection being carried out. The wire value (what the API
 * emits and accepts) is the lowercase form supplied by {@link #getValue()}; the
 * constant name is what {@code @Enumerated(STRING)} stores in the database. A
 * distinct wire value is required because one member ({@code FINAL}) collides
 * with a Java reserved word and several inspection statuses use hyphenated forms.
 */
public enum InspectionType {
    SAFETY("safety"),
    QUALITY("quality"),
    PROGRESS("progress"),
    FINAL("final"),
    STRUCTURAL("structural"),
    ELECTRICAL("electrical"),
    PLUMBING("plumbing"),
    FINISHING("finishing"),
    COMPLIANCE("compliance");

    private final String value;

    InspectionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static InspectionType fromValue(String value) {
        for (InspectionType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown inspection type: " + value);
    }
}

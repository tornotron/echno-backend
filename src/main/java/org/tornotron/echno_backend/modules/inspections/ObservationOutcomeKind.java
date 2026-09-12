package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What an accepted or modified observation turned into. NONE is a noted finding with no action; the others name the record outcomeRef points at.
 * The constant name is the database representation stored by {@code @Enumerated(STRING)};
 * the slug is the wire value.
 */
public enum ObservationOutcomeKind {
    NONE("none"), CHECK_ITEM("check-item"), DEFECT("defect"), INSPECTION("inspection"), NCR("ncr");

    private final String value;

    ObservationOutcomeKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ObservationOutcomeKind fromValue(String value) {
        for (ObservationOutcomeKind candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown ObservationOutcomeKind: " + value);
    }
}

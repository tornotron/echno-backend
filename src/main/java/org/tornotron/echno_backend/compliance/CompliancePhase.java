package org.tornotron.echno_backend.compliance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stage of the construction lifecycle a compliance applies to. Compliances fall
 * into three phases: obtained before work starts ({@code PRE_CONSTRUCTION}), held
 * or renewed while work runs ({@code ONGOING}), and obtained on completion
 * ({@code POST_CONSTRUCTION}). The wire value is the hyphenated form from
 * {@link #getValue()}; the constant name is the database representation stored by
 * {@code @Enumerated(STRING)}, mirroring the inspection enums.
 */
public enum CompliancePhase {
    PRE_CONSTRUCTION("pre-construction"),
    ONGOING("ongoing"),
    POST_CONSTRUCTION("post-construction");

    private final String value;

    CompliancePhase(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static CompliancePhase fromValue(String value) {
        for (CompliancePhase phase : values()) {
            if (phase.value.equalsIgnoreCase(value)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unknown compliance phase: " + value);
    }
}

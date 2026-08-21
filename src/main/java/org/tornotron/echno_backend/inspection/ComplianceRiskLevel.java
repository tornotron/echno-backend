package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Risk severity attached to a compliance-type inspection and to the compliance
 * rule it was generated from. The wire value is the lowercase form from
 * {@link #getValue()}; the constant name is the database representation stored
 * by {@code @Enumerated(STRING)}, mirroring the other inspection enums.
 */
public enum ComplianceRiskLevel {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    CRITICAL("critical");

    private final String value;

    ComplianceRiskLevel(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ComplianceRiskLevel fromValue(String value) {
        for (ComplianceRiskLevel level : values()) {
            if (level.value.equalsIgnoreCase(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown compliance risk level: " + value);
    }
}

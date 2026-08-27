package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How serious a defect raised during an inspection is. Replaces the free text the
 * column previously held; the wire values are unchanged from the string union the
 * web contract already documents ('critical' | 'major' | 'minor'), so promoting
 * the field to an enum tightens validation without altering the API payload. The
 * constant name is the database representation stored by
 * {@code @Enumerated(STRING)}.
 */
public enum DefectSeverity {
    CRITICAL("critical"),
    MAJOR("major"),
    MINOR("minor");

    private final String value;

    DefectSeverity(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DefectSeverity fromValue(String value) {
        for (DefectSeverity severity : values()) {
            if (severity.value.equalsIgnoreCase(value)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown defect severity: " + value);
    }
}

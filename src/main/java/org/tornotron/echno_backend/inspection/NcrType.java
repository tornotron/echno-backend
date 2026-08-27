package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether a non-conformance is a quality or a safety matter. This is not a label:
 * it decides who may close the NCR. A quality NCR is closed by a QA engineer and a
 * safety one by a safety officer, and neither may close the other's, which is the
 * separation the functional spec asks for and the reason the type is fixed when
 * the NCR is raised.
 *
 * <p>The wire value is the lowercase form from {@link #getValue()}; the constant
 * name is the database representation stored by {@code @Enumerated(STRING)}.
 */
public enum NcrType {
    QUALITY("quality"),
    SAFETY("safety");

    private final String value;

    NcrType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static NcrType fromValue(String value) {
        for (NcrType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown NCR type: " + value);
    }

    /**
     * The category of inspection an NCR of this type is raised from. A quality
     * non-conformance belongs to a QA/QC inspection and a safety one to a safety
     * inspection, so an NCR raised against an inspection of another category has
     * the wrong type on it.
     */
    public static NcrType forCategory(InspectionCategory category) {
        return category == InspectionCategory.SAFETY ? SAFETY : QUALITY;
    }
}

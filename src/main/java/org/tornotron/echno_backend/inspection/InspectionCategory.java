package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Top-level grouping an inspection belongs to, and the axis the UI and reporting
 * filter on. Distinct from {@link InspectionType}, which stays as the finer free
 * label an inspector picks; the category is derived from it by
 * {@link #defaultFor(InspectionType)} unless the caller states one explicitly.
 * The wire value is the hyphenated lowercase form from {@link #getValue()}; the
 * constant name is the database representation stored by
 * {@code @Enumerated(STRING)}, mirroring the other inspection enums.
 */
public enum InspectionCategory {
    SAFETY("safety"),
    QA_QC("qa-qc"),
    COMPLIANCE("compliance"),
    OTHER("other");

    private final String value;

    InspectionCategory(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static InspectionCategory fromValue(String value) {
        for (InspectionCategory category : values()) {
            if (category.value.equalsIgnoreCase(value)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown inspection category: " + value);
    }

    /**
     * The category an inspection falls into when only its {@link InspectionType}
     * is known. This is the same mapping the Liquibase backfill applies to rows
     * created before the category column existed, so a legacy row and a newly
     * created one of the same type land in the same bucket.
     *
     * @param type the inspection type, may be null
     * @return the matching category, or {@link #OTHER} when the type is null
     */
    public static InspectionCategory defaultFor(InspectionType type) {
        if (type == null) {
            return OTHER;
        }
        return switch (type) {
            case SAFETY -> SAFETY;
            case COMPLIANCE -> COMPLIANCE;
            case QUALITY, STRUCTURAL, ELECTRICAL, PLUMBING, FINISHING, PROGRESS, FINAL -> QA_QC;
        };
    }
}

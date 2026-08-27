package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The construction stage or trade a QA/QC inspection is carried out against, one
 * value per FR-QA requirement of the inspection functional spec. Nullable on an
 * inspection and populated only for the {@link InspectionCategory#QA_QC} and
 * {@link InspectionCategory#OTHER} categories; safety and compliance inspections
 * leave it unset. The wire value is the hyphenated lowercase form from
 * {@link #getValue()}; the constant name is the database representation stored by
 * {@code @Enumerated(STRING)}.
 */
public enum InspectionTrade {
    PRE_CONSTRUCTION_DOCUMENTATION("pre-construction-documentation"),
    SHUTTERING_FORMWORK("shuttering-formwork"),
    REINFORCEMENT("reinforcement"),
    RCC("rcc"),
    MASONRY("masonry"),
    PLASTERING("plastering"),
    WATERPROOFING("waterproofing"),
    FLOORING("flooring"),
    FABRICATION("fabrication"),
    ALUMINIUM_UPVC("aluminium-upvc"),
    ELECTRICAL_FIXTURES("electrical-fixtures"),
    PLUMBING_FIXTURES("plumbing-fixtures"),
    SANITARY_FIXTURES("sanitary-fixtures"),
    FINISHING("finishing"),
    DIMENSIONAL_CHECK("dimensional-check"),
    PROGRESS_CHECK("progress-check");

    private final String value;

    InspectionTrade(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static InspectionTrade fromValue(String value) {
        for (InspectionTrade trade : values()) {
            if (trade.value.equalsIgnoreCase(value)) {
                return trade;
            }
        }
        throw new IllegalArgumentException("Unknown inspection trade: " + value);
    }
}

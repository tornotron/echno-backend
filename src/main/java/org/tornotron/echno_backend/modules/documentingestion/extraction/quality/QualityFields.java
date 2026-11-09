package org.tornotron.echno_backend.modules.documentingestion.extraction.quality;

import java.util.List;

/**
 * The payload of a {@code QUALITY_REQUIREMENT} candidate (design section 4.4), with the same keys as the
 * corpus labels. The natural key is the material or subject as printed. {@link #STANDARDS} and
 * {@link #REQUIRED_DOCUMENTS} are lists; the rest are text as printed.
 */
public final class QualityFields {

    public static final String ACTIVITY = "activity";
    public static final String MATERIAL = "material";
    public static final String GRADE = "grade";
    public static final String STANDARDS = "standards";
    public static final String TEST = "test";
    public static final String FREQUENCY = "frequency";
    public static final String ACCEPTANCE_CRITERION = "acceptance_criterion";
    public static final String HOLD_OR_WITNESS_POINT = "hold_or_witness_point";
    public static final String REQUIRED_DOCUMENTS = "required_documents";

    public static final List<String> ALL = List.of(ACTIVITY, MATERIAL, GRADE, STANDARDS, TEST, FREQUENCY,
            ACCEPTANCE_CRITERION, HOLD_OR_WITNESS_POINT, REQUIRED_DOCUMENTS);

    /** Values a model may never supply from its own knowledge: each must be found, as given, in its quote. */
    public static final List<String> QUOTE_ONLY = List.of(FREQUENCY, ACCEPTANCE_CRITERION);

    /**
     * Values kept only when each of their words is printed in their quote. These are what a reader of a
     * specification is apt to add from what such a requirement usually carries, such as a slump test or a
     * pre-pour hold point from a sentence that names neither.
     */
    public static final List<String> WORDS_PRINTED = List.of(TEST, HOLD_OR_WITNESS_POINT, REQUIRED_DOCUMENTS);

    /** Set when the specification states no frequency for the requirement; the value stays empty. */
    public static final String FREQUENCY_NOT_STATED = "FREQUENCY_NOT_STATED";
    /** Set when the specification states no acceptance criterion for the requirement; the value stays empty. */
    public static final String ACCEPTANCE_NOT_STATED = "ACCEPTANCE_NOT_STATED";

    private QualityFields() {
    }

    /** The flag that names a quote-only field as not stated. */
    public static String notStatedFlag(String field) {
        return FREQUENCY.equals(field) ? FREQUENCY_NOT_STATED : ACCEPTANCE_NOT_STATED;
    }

    /** The field's name as a reader says it. */
    public static String label(String field) {
        return ACCEPTANCE_CRITERION.equals(field) ? "acceptance criterion" : field.replace('_', ' ');
    }
}

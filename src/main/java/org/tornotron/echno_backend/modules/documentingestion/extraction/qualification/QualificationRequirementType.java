package org.tornotron.echno_backend.modules.documentingestion.extraction.qualification;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The five bidder qualification requirements read from a tender (design section 4.7). Each is a
 * {@code QUALIFICATION_REQUIREMENT} candidate whose natural key starts with {@link #key()}. The first
 * three are compared with the company profile automatically; equipment and personnel are text, so a
 * person sets their status.
 */
public enum QualificationRequirementType {

    TURNOVER("turnover", "Turnover", "minimum annual turnover the bidder must have had", true),
    SIMILAR_PROJECTS("similar_projects", "Similar projects",
            "the number of similar works the bidder must have completed, and the value each must reach", true),
    EXPERIENCE("experience", "Experience", "years of experience in the line of work the bidder must have", true),
    EQUIPMENT("equipment", "Equipment", "machinery or plant the bidder must own or have access to", false),
    PERSONNEL("personnel", "Key personnel", "key staff the bidder must deploy on the work", false);

    /** Payload field: the amount, count, years or list as printed. */
    public static final String VALUE = "value";
    /** Payload field, similar projects only: the value each work must reach, as printed. */
    public static final String MIN_VALUE = "min_value";
    /** Payload field, turnover and similar projects: the years counted over, as printed. */
    public static final String PERIOD = "period";

    private final String key;
    private final String label;
    private final String meaning;
    private final boolean numeric;

    QualificationRequirementType(String key, String label, String meaning, boolean numeric) {
        this.key = key;
        this.label = label;
        this.meaning = meaning;
        this.numeric = numeric;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String meaning() {
        return meaning;
    }

    /** Compared with the profile automatically; otherwise a person sets the status. */
    public boolean numeric() {
        return numeric;
    }

    /** Whether the requirement carries a minimum value per work. */
    public boolean hasMinValue() {
        return this == SIMILAR_PROJECTS;
    }

    /** Whether the requirement can be counted over a period of years. */
    public boolean hasPeriod() {
        return this == TURNOVER || this == SIMILAR_PROJECTS;
    }

    public static Optional<QualificationRequirementType> of(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String k = key.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.key.equals(k)).findFirst();
    }

    /** The type a natural key names, from its part before the first {@code |}. */
    public static Optional<QualificationRequirementType> ofNaturalKey(String naturalKey) {
        if (naturalKey == null) {
            return Optional.empty();
        }
        int bar = naturalKey.indexOf('|');
        return of(bar < 0 ? naturalKey : naturalKey.substring(0, bar));
    }

    /**
     * The natural key: the type, then the value and the minimum value, lower case with every run of
     * other characters as one dash, so the same requirement read twice keys the same.
     */
    public String naturalKey(String value, String minValue) {
        StringBuilder sb = new StringBuilder(key).append('|').append(slug(value));
        if (minValue != null && !minValue.isBlank()) {
            sb.append('|').append(slug(minValue));
        }
        return sb.length() <= 190 ? sb.toString() : sb.substring(0, 190);
    }

    private static String slug(String text) {
        if (text == null) {
            return "";
        }
        String s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.length() <= 80 ? s : s.substring(0, 80);
    }
}

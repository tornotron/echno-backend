package org.tornotron.echno_backend.modules.documentingestion.extraction.project;

import java.util.Locale;
import java.util.Optional;

/**
 * The facts of a tender that set up a project (design section 4.1), each a {@code PROJECT_FIELD}
 * candidate whose natural key is {@link #key()} and whose payload is {@code {"value": ...}} as printed.
 * Two land on the {@code Project} itself; the rest have no home there and go to the tender profile.
 */
public enum ProjectField {
    PROJECT_NAME("project_name", Target.PROJECT, "the name of the work or project"),
    LOCATION("location", Target.PROJECT, "where the work is, as an address or a place"),
    CLIENT("client", Target.PROFILE, "the employer, owner or department inviting the tender"),
    ESTIMATED_VALUE("estimated_value", Target.PROFILE, "the estimated cost or value put to tender"),
    EMD("emd", Target.PROFILE, "the earnest money deposit or bid security"),
    TENDER_FEE("tender_fee", Target.PROFILE, "the tender fee or the cost of the bid document"),
    BID_DUE_DATE("bid_due_date", Target.PROFILE, "the last date, and time if printed, for submitting bids"),
    BID_VALIDITY("bid_validity", Target.PROFILE, "how long a bid must stay valid"),
    DURATION("duration", Target.PROFILE, "the time allowed to complete the work"),
    CONSULTANT("consultant", Target.PROFILE, "the consultant or architect named for the project"),
    ENGINEER("engineer", Target.PROFILE, "the engineer, engineer-in-charge or project manager named for the employer"),
    SCOPE("scope", Target.PROFILE, "a short statement of the scope of work, copied as printed");

    /** Where an accepted value is written. */
    public enum Target {
        PROJECT, PROFILE
    }

    /** The one payload field of a project field candidate. */
    public static final String VALUE = "value";

    private final String key;
    private final Target target;
    private final String meaning;

    ProjectField(String key, Target target, String meaning) {
        this.key = key;
        this.target = target;
        this.meaning = meaning;
    }

    public String key() {
        return key;
    }

    public Target target() {
        return target;
    }

    public String meaning() {
        return meaning;
    }

    public static Optional<ProjectField> of(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String k = key.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (ProjectField f : values()) {
            if (f.key.equals(k)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}

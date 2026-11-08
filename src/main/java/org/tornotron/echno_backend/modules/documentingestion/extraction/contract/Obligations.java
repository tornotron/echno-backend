package org.tornotron.echno_backend.modules.documentingestion.extraction.contract;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The payload of an {@code OBLIGATION} candidate and the small readings made of it: the clause number
 * without its label, the natural key ({@code clause|deadline}, as the corpus labels it), and a deadline
 * in days when it reads plainly as a number of days or weeks.
 */
public final class Obligations {

    public static final String CLAUSE = "clause";
    public static final String DEADLINE = "deadline";
    public static final String RESPONSIBLE_PARTY = "responsible_party";
    public static final String TOPIC = "topic";
    public static final String TRIGGER = "trigger";
    public static final String REQUIRED_DOCUMENT = "required_document";
    public static final String FINANCIAL_IMPACT = "financial_impact";

    /** Every payload field, in the order the register shows them. */
    public static final List<String> FIELDS = List.of(CLAUSE, DEADLINE, RESPONSIBLE_PARTY, TOPIC, TRIGGER,
            REQUIRED_DOCUMENT, FINANCIAL_IMPACT);

    private static final Pattern CLAUSE_LABEL = Pattern.compile(
            "^(?:(?:(?:general|special)\\s+conditions?(?:\\s+of\\s+contract)?|gcc|scc|sub[\\s-]*clause|clause|cl|"
                    + "article|art|section|paragraph|para|no)\\b\\.?|[:#\\s])+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DAYS = Pattern.compile(
            "^(?:within\\s+|not\\s+later\\s+than\\s+|before\\s+)?(\\d{1,4})\\s*(?:\\(\\s*[a-z\\s-]+\\)\\s*)?"
                    + "(calendar\\s+|working\\s+)?(days?|weeks?)\\b.*$", Pattern.CASE_INSENSITIVE);

    private Obligations() {
    }

    /** The clause number as printed, without words such as "Clause" or "GCC" before it. */
    public static String clause(String raw) {
        if (raw == null) {
            return null;
        }
        String s = CLAUSE_LABEL.matcher(raw.strip()).replaceFirst("").strip();
        while (!s.isEmpty() && ".,;:)".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s.isEmpty() ? null : s;
    }

    /** {@code clause|deadline}, the natural key of an obligation, with either part empty when not given. */
    public static String naturalKey(String clause, String deadline) {
        return (clause == null ? "" : clause) + "|" + (deadline == null ? "" : deadline.strip());
    }

    /**
     * The deadline in days when it reads plainly as a number of calendar days or weeks ("15 days",
     * "within 28 days", "4 weeks"); empty for working days, months and anything else, which stay as
     * printed only.
     */
    public static Optional<Integer> days(String deadline) {
        if (deadline == null) {
            return Optional.empty();
        }
        Matcher m = DAYS.matcher(deadline.strip());
        if (!m.matches() || m.group(2) != null && m.group(2).toLowerCase(Locale.ROOT).startsWith("working")) {
            return Optional.empty();
        }
        int n = Integer.parseInt(m.group(1));
        return Optional.of(m.group(3).toLowerCase(Locale.ROOT).startsWith("week") ? n * 7 : n);
    }
}

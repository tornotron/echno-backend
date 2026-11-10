package org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqField;

/**
 * Matches a change, read from a corrigendum or from a revised file, to the earlier candidate it changes,
 * by natural key: a BOQ item by its code, a project field by its name, a milestone by its name. Works out
 * what follows by arithmetic on sourced numbers: for a BOQ quantity the variance and its money impact at the
 * item's rate, for a rate the impact on the item's quantity (design section 4.8). A change that matches
 * nothing is flagged and kept, never dropped.
 */
public final class ChangeMatcher {

    public static final String TARGET_KIND = "targetKind";
    public static final String TARGET = "target";
    public static final String FIELD = "field";
    public static final String OLD_VALUE = "oldValue";
    public static final String NEW_VALUE = "newValue";
    public static final String PRIOR_VALUE = "priorValue";
    public static final String QUANTITY_VARIANCE = "quantityVariance";
    public static final String MONEY_IMPACT = "moneyImpact";
    public static final String ORIGIN = "origin";
    public static final String ORIGIN_PROSE = "CORRIGENDUM";
    public static final String ORIGIN_REVISED_FILE = "REVISED_FILE";

    public static final String UNMATCHED = "UNMATCHED";
    public static final String OLD_VALUE_DIFFERS = "OLD_VALUE_DIFFERS";
    public static final String TENDER_IMPACT = "TENDER_IMPACT";
    public static final String ITEM_ADDED = "ITEM_ADDED";
    public static final String ITEM_REMOVED = "ITEM_REMOVED";

    /** A number as printed, with an optional currency sign before it and one unit word after it. */
    private static final Pattern MEASURE = Pattern.compile(
            "(?i)^(?:rs\\.?|inr|₹)?\\s*(-?\\d[\\d,\\s]*(?:\\.\\d+)?)\\s*(?:(?:/|per\\s+)?\\s*\\p{L}[\\p{L}\\p{N}.]*)?$");

    private ChangeMatcher() {
    }

    /** What matching found: the prior candidate, if any, and the payload and flags to add. */
    public record Match(UUID priorId, Map<String, Object> additions, List<CandidateFlag> flags) {
    }

    /** The natural key of a change: what it changes and which field. */
    public static String naturalKey(String targetKind, String target, String field) {
        return targetKind + ":" + target + ":" + (field == null ? "" : field);
    }

    /** The kind a change names, read leniently; BOQ item when it names a BOQ field. */
    public static Optional<CandidateKind> kind(Object raw, Object field) {
        if (raw != null) {
            String k = raw.toString().strip().toUpperCase(Locale.ROOT).replace(' ', '_');
            for (CandidateKind c : CandidateKind.values()) {
                if (c != CandidateKind.CHANGE && c.name().equals(k)) {
                    return Optional.of(c);
                }
            }
        }
        if (field != null && BoqField.ofKey(field.toString()).isPresent()) {
            return Optional.of(CandidateKind.BOQ_ITEM);
        }
        return Optional.empty();
    }

    /**
     * Finds the prior candidate a change names among the package's candidates of that kind, preferring an
     * applied one, then an accepted one, then the latest proposed one; rejected and superseded rows never
     * match.
     *
     * @param changeDocumentId The change's own document, whose rows are never its prior
     * @param tender Whether the package is at stage {@code TENDER}, where a change may affect bids
     */
    public static Match match(Map<String, Object> change, UUID changeDocumentId, List<Candidate> priors,
                              boolean tender) {
        return match(change, changeDocumentId, priors, tender, ChangeMatcher::values);
    }

    /**
     * As {@link #match(Map, UUID, List, boolean)}, with the values a prior holds now worked out by
     * {@code current}. A prior already on a live record and superseded by a change applied to it still
     * matches, as the identity of that record, with the values the applied changes left on it.
     */
    public static Match match(Map<String, Object> change, UUID changeDocumentId, List<Candidate> priors,
                              boolean tender, Function<Candidate, Map<String, Object>> current) {
        Map<String, Object> additions = new LinkedHashMap<>();
        List<CandidateFlag> flags = new ArrayList<>();
        Optional<CandidateKind> kind = kind(change.get(TARGET_KIND), change.get(FIELD));
        String target = text(change.get(TARGET));
        String field = text(change.get(FIELD));
        Candidate prior = kind.isEmpty() || target == null ? null : priors.stream()
                .filter(c -> c.getKind() == kind.get())
                .filter(c -> changeDocumentId == null || !changeDocumentId.equals(c.getDocumentId()))
                .filter(ChangeMatcher::eligible)
                .filter(c -> sameKey(kind.get(), c, target))
                .max(Comparator.comparingInt(ChangeMatcher::rank).thenComparing(Candidate::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (tender) {
            flags.add(new CandidateFlag(TENDER_IMPACT, "This changes the tender before award. Check whether bids "
                    + "already prepared need revising."));
        }
        if (prior == null) {
            flags.add(new CandidateFlag(UNMATCHED, "No earlier value matches this change, so it stays on the "
                    + "unmatched list. Check it against the documents by hand."));
            return new Match(null, additions, flags);
        }
        Map<String, Object> values = current.apply(prior);
        String priorField = payloadField(kind.get(), field);
        Object priorValue = priorField == null ? null : values.get(priorField);
        if (priorValue != null) {
            additions.put(PRIOR_VALUE, priorValue.toString());
        }
        BoqField boqField = kind.get() == CandidateKind.BOQ_ITEM && field != null ? BoqField.ofKey(field).orElse(null) : null;
        String oldValue = text(change.get(OLD_VALUE));
        if (oldValue != null && priorValue != null && !sameValue(boqField, oldValue, priorValue.toString())) {
            flags.add(new CandidateFlag(OLD_VALUE_DIFFERS, "The change gives the old value as \"" + oldValue
                    + "\", but the earlier value is \"" + priorValue + "\"."));
        }
        if (kind.get() == CandidateKind.BOQ_ITEM) {
            impact(boqField, measure(priorValue), measure(change.get(NEW_VALUE)), values, additions);
        }
        return new Match(prior.getId(), additions, flags);
    }

    /** Variance and money impact of a BOQ quantity or rate change, at the item's other figure. */
    static void impact(BoqField field, BigDecimal before, BigDecimal after, Map<String, Object> item,
                       Map<String, Object> additions) {
        if (field == null || before == null || after == null) {
            return;
        }
        BigDecimal delta = after.subtract(before);
        if (field == BoqField.QUANTITY) {
            additions.put(QUANTITY_VARIANCE, delta.stripTrailingZeros().toPlainString());
            BigDecimal rate = measure(item.get(BoqField.RATE.key()));
            if (rate != null) {
                additions.put(MONEY_IMPACT, delta.multiply(rate).stripTrailingZeros().toPlainString());
            }
        } else if (field == BoqField.RATE) {
            BigDecimal quantity = measure(item.get(BoqField.QUANTITY.key()));
            if (quantity != null) {
                additions.put(MONEY_IMPACT, delta.multiply(quantity).stripTrailingZeros().toPlainString());
            }
        }
    }

    /**
     * The prior candidate's payload field a change's field names; a project field has one value, so a
     * change to it needs no field. Null when the change names none and the kind needs one.
     */
    public static String payloadField(CandidateKind kind, String field) {
        if (kind == CandidateKind.PROJECT_FIELD) {
            return "value";
        }
        if (field == null) {
            return null;
        }
        if (kind == CandidateKind.BOQ_ITEM) {
            return BoqField.ofKey(field).map(BoqField::key).orElse(field);
        }
        return field;
    }

    /** What a candidate holds: its accepted payload, or what was read. */
    public static Map<String, Object> values(Candidate c) {
        return c.getAcceptedPayload() != null ? c.getAcceptedPayload() : c.getPayload();
    }

    /** Whether a candidate may be the prior of a change. */
    static boolean eligible(Candidate c) {
        return c.getStatus() != CandidateStatus.REJECTED
                && (c.getStatus() != CandidateStatus.SUPERSEDED || c.getAppliedTargetId() != null);
    }

    private static boolean sameKey(CandidateKind kind, Candidate c, String target) {
        String t = norm(target);
        if (kind == CandidateKind.BOQ_ITEM) {
            Map<String, Object> p = c.getAcceptedPayload() != null ? c.getAcceptedPayload() : c.getPayload();
            Object code = p.get(BoqField.ITEM_CODE.key());
            return code != null && norm(code.toString()).equals(t);
        }
        return c.getNaturalKey() != null && norm(c.getNaturalKey()).equals(t);
    }

    private static int rank(Candidate c) {
        return switch (c.getStatus()) {
            case APPLIED, SUPERSEDED -> 3;
            case ACCEPTED, APPLY_FAILED -> 2;
            default -> 1;
        };
    }

    /** Whether two values are the same: equal as numbers when both are, else as text ignoring case and spacing. */
    public static boolean same(String a, String b) {
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x != null && y != null) {
            return x.compareTo(y) == 0;
        }
        return norm(a).equals(norm(b));
    }

    /** Whether two values of a BOQ field are the same, a quantity or rate compared as a measure. */
    public static boolean sameValue(BoqField field, String a, String b) {
        if (field == BoqField.QUANTITY || field == BoqField.RATE || field == BoqField.AMOUNT) {
            BigDecimal x = measure(a);
            BigDecimal y = measure(b);
            if (x != null && y != null) {
                return x.compareTo(y) == 0;
            }
        }
        return same(a, b);
    }

    /**
     * The number of a value printed as a measure: digits with grouping, an optional currency sign before and
     * one unit word after, as in {@code 920 cum}, {@code 1 200 cum} or {@code Rs. 640 per sqm}. Null when
     * anything else is left, as in {@code 1/2} or {@code 2 coats of paint}, so a part of a value is never
     * taken for the whole.
     */
    public static BigDecimal measure(Object value) {
        BigDecimal plain = number(value);
        if (plain != null || value == null || value instanceof Number) {
            return plain;
        }
        Matcher m = MEASURE.matcher(value.toString().strip());
        if (!m.matches()) {
            return null;
        }
        String digits = m.group(1).replaceAll("[,\\s]", "");
        return digits.matches("-?\\d+(\\.\\d+)?") ? new BigDecimal(digits) : null;
    }

    /** An item code as compared: case and spacing ignored. */
    public static String codeKey(String code) {
        return norm(code);
    }

    /** A plain number, with digit grouping and a currency sign allowed; null for anything else. */
    public static BigDecimal number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = value.toString().replaceAll("[,\\s]", "").replaceFirst("^(?i)(rs\\.?|inr|₹)", "");
        if (!s.matches("-?\\d+(\\.\\d+)?")) {
            return null;
        }
        return new BigDecimal(s);
    }

    private static String norm(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String text(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().strip();
        return s.isEmpty() ? null : s;
    }
}

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
        Map<String, Object> additions = new LinkedHashMap<>();
        List<CandidateFlag> flags = new ArrayList<>();
        Optional<CandidateKind> kind = kind(change.get(TARGET_KIND), change.get(FIELD));
        String target = text(change.get(TARGET));
        String field = text(change.get(FIELD));
        Candidate prior = kind.isEmpty() || target == null ? null : priors.stream()
                .filter(c -> c.getKind() == kind.get())
                .filter(c -> changeDocumentId == null || !changeDocumentId.equals(c.getDocumentId()))
                .filter(c -> c.getStatus() != CandidateStatus.REJECTED && c.getStatus() != CandidateStatus.SUPERSEDED)
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
        Map<String, Object> current = prior.getAcceptedPayload() != null ? prior.getAcceptedPayload() : prior.getPayload();
        String priorField = field == null ? null : payloadField(kind.get(), field);
        Object priorValue = priorField == null ? null : current.get(priorField);
        if (priorValue != null) {
            additions.put(PRIOR_VALUE, priorValue.toString());
        }
        String oldValue = text(change.get(OLD_VALUE));
        if (oldValue != null && priorValue != null && !same(oldValue, priorValue.toString())) {
            flags.add(new CandidateFlag(OLD_VALUE_DIFFERS, "The change gives the old value as \"" + oldValue
                    + "\", but the earlier value is \"" + priorValue + "\"."));
        }
        if (kind.get() == CandidateKind.BOQ_ITEM) {
            impact(BoqField.ofKey(field).orElse(null), number(priorValue), number(change.get(NEW_VALUE)), current, additions);
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
            BigDecimal rate = number(item.get(BoqField.RATE.key()));
            if (rate != null) {
                additions.put(MONEY_IMPACT, delta.multiply(rate).stripTrailingZeros().toPlainString());
            }
        } else if (field == BoqField.RATE) {
            BigDecimal quantity = number(item.get(BoqField.QUANTITY.key()));
            if (quantity != null) {
                additions.put(MONEY_IMPACT, delta.multiply(quantity).stripTrailingZeros().toPlainString());
            }
        }
    }

    /** The prior candidate's payload field a change's field names. */
    public static String payloadField(CandidateKind kind, String field) {
        if (kind == CandidateKind.BOQ_ITEM) {
            return BoqField.ofKey(field).map(BoqField::key).orElse(field);
        }
        if (kind == CandidateKind.PROJECT_FIELD) {
            return "value";
        }
        return field;
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
            case APPLIED -> 3;
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

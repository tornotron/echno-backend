package org.tornotron.echno_backend.modules.documentingestion.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The part of the corpus scorer ({@code ingest score} in tornotron/echno-datasets) that the deterministic
 * gates need, so the corpus harness can assert them in the build. The scorer stays the record: this follows
 * its comparison rules (folded text, exact decimals, one-to-one pairing on the natural key, labelled pages)
 * for the measures it covers, and the operator runs the scorer on the same predictions for the gate table.
 */
final class CorpusGates {

    static final List<String> BOQ_NUMBER_FIELDS = List.of("quantity", "rate", "amount");

    private static final Pattern DROP = Pattern.compile("[\\s,:;_'\"()\\-–]+");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern CURRENCY = Pattern.compile("^(rs\\.?|inr|₹)\\s*", Pattern.CASE_INSENSITIVE);

    private CorpusGates() {
    }

    /** A count of hits over a total. A total of zero is "not measured", never a pass. */
    record Tally(int hits, int total) {

        static final Tally NONE = new Tally(0, 0);

        Tally plus(int hits, int total) {
            return new Tally(this.hits + hits, this.total + total);
        }

        Tally plus(Tally other) {
            return plus(other.hits, other.total);
        }

        boolean measured() {
            return total > 0;
        }

        /** Exact: 199 of 200 meets 0.995. */
        boolean meets(String threshold) {
            return measured() && BigDecimal.valueOf(hits).compareTo(new BigDecimal(threshold)
                    .multiply(BigDecimal.valueOf(total))) >= 0;
        }

        String show() {
            return measured() ? String.format(Locale.ROOT, "%d of %d (%.4f)", hits, total, (double) hits / total)
                    : "not measured";
        }
    }

    /** The labels paired one to one with predictions on the natural key, and what was left on each side. */
    record Pairing(List<JsonNode[]> pairs, int missed, int surplus) {

        Tally captured() {
            return new Tally(pairs.size(), pairs.size() + missed);
        }
    }

    /**
     * Folds case and drops spacing, the punctuation {@code , : ; _ ' " ( ) -} and the en dash. Dots and percent
     * signs stay.
     */
    static String normalize(JsonNode value) {
        String text = Normalizer.normalize(value.isTextual() ? value.asText() : value.toString(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        text = DROP.matcher(text).replaceAll("");
        if (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** The value as one exact number, or null when it is not plainly one number. */
    static BigDecimal number(JsonNode value) {
        if (value == null || value.isNull() || value.isBoolean()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (!value.isTextual()) {
            return null;
        }
        String text = CURRENCY.matcher(value.asText().strip()).replaceFirst("");
        if (text.endsWith("/-")) {
            text = text.substring(0, text.length() - 2);
        }
        text = text.replace(",", "").strip();
        return NUMBER.matcher(text).matches() ? new BigDecimal(text) : null;
    }

    static boolean absent(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode();
    }

    /** Equal as numbers when both sides read as one number, else equal as folded text; or equal to an accepted spelling. */
    static boolean equal(JsonNode label, JsonNode predicted, JsonNode accept) {
        if (absent(label) || absent(predicted)) {
            return absent(label) && absent(predicted);
        }
        List<JsonNode> options = new ArrayList<>();
        options.add(label);
        if (accept != null && accept.isArray()) {
            accept.forEach(options::add);
        }
        for (JsonNode option : options) {
            BigDecimal expected = number(option);
            BigDecimal found = number(predicted);
            if (expected != null && found != null) {
                if (expected.compareTo(found) == 0) {
                    return true;
                }
            } else if (normalize(option).equals(normalize(predicted))) {
                return true;
            }
        }
        return false;
    }

    /** The key a candidate is paired on: the natural key, or a BOQ item's {@code payload.code}. */
    static String key(JsonNode candidate) {
        JsonNode natural = candidate.path("natural_key");
        if (absent(natural) && "BOQ_ITEM".equals(candidate.path("kind").asText())
                && !absent(candidate.path("payload").path("code"))) {
            natural = candidate.path("payload").path("code");
        }
        return absent(natural) ? "" : normalize(natural);
    }

    /** A prediction citing only pages outside the labelled pages is neither right nor wrong. */
    static boolean inScope(JsonNode prediction, JsonNode coverage) {
        String pages = coverage.path("pages").asText("");
        if (pages.isBlank()) {
            return true;
        }
        String[] range = pages.split("-");
        int first = Integer.parseInt(range[0].strip());
        int last = Integer.parseInt(range[range.length - 1].strip());
        boolean cited = false;
        for (JsonNode ref : prediction.path("source_refs")) {
            if (ref.path("page").canConvertToInt()) {
                cited = true;
                int page = ref.path("page").asInt();
                if (page >= first && page <= last) {
                    return true;
                }
            }
        }
        return !cited;
    }

    /**
     * Pairs one to one on the key. Where a key repeats, pairs that cite the same place go first, then those with
     * the most equal values, as the scorer does.
     */
    static Pairing pair(List<JsonNode> labels, List<JsonNode> predictions) {
        Map<String, List<JsonNode>> wanted = group(labels);
        Map<String, List<JsonNode>> offered = group(predictions);
        Set<String> keys = new HashSet<>(wanted.keySet());
        keys.addAll(offered.keySet());
        List<JsonNode[]> pairs = new ArrayList<>();
        int missed = 0;
        int surplus = 0;
        for (String key : keys) {
            List<JsonNode> ls = wanted.getOrDefault(key, List.of());
            List<JsonNode> ps = offered.getOrDefault(key, List.of());
            List<int[]> ranked = new ArrayList<>();
            for (int i = 0; i < ls.size(); i++) {
                for (int j = 0; j < ps.size(); j++) {
                    ranked.add(new int[] {samePlace(ls.get(i), ps.get(j)), sameValues(ls.get(i), ps.get(j)), i, j});
                }
            }
            ranked.sort(Comparator.<int[]>comparingInt(r -> -r[0]).thenComparingInt(r -> -r[1])
                    .thenComparingInt(r -> r[2]).thenComparingInt(r -> r[3]));
            Set<Integer> usedLabels = new HashSet<>();
            Set<Integer> usedPredictions = new HashSet<>();
            for (int[] r : ranked) {
                if (!usedLabels.contains(r[2]) && !usedPredictions.contains(r[3])) {
                    usedLabels.add(r[2]);
                    usedPredictions.add(r[3]);
                    pairs.add(new JsonNode[] {ls.get(r[2]), ps.get(r[3])});
                }
            }
            missed += ls.size() - usedLabels.size();
            surplus += ps.size() - usedPredictions.size();
        }
        return new Pairing(pairs, missed, surplus);
    }

    /**
     * Numbers exact on captured BOQ rows: each of quantity, rate and amount the label or the prediction states.
     * A number predicted where the document leaves the cell blank counts as wrong.
     */
    static Tally boqNumbers(Pairing pairing) {
        Tally out = Tally.NONE;
        for (JsonNode[] p : pairing.pairs()) {
            for (String field : BOQ_NUMBER_FIELDS) {
                JsonNode expected = p[0].path("payload").get(field);
                JsonNode found = p[1].path("payload").get(field);
                if (absent(expected) && absent(found)) {
                    continue;
                }
                if (absent(expected)) {
                    out = out.plus(0, 1);
                    continue;
                }
                BigDecimal e = number(expected);
                BigDecimal f = number(found);
                out = out.plus(e != null && f != null && e.compareTo(f) == 0 ? 1 : 0, 1);
            }
        }
        return out;
    }

    /** Drawing number and revision: a labelled sheet counts when it is paired and its revision is equal. */
    static Tally titleBlocks(List<JsonNode> labels, Pairing pairing) {
        int right = 0;
        for (JsonNode[] p : pairing.pairs()) {
            if (equal(p[0].path("payload").get("revision"), p[1].path("payload").get("revision"),
                    p[0].path("accept").get("revision"))) {
                right++;
            }
        }
        return new Tally(right, labels.size());
    }

    private static Map<String, List<JsonNode>> group(List<JsonNode> candidates) {
        Map<String, List<JsonNode>> out = new LinkedHashMap<>();
        for (JsonNode c : candidates) {
            out.computeIfAbsent(key(c), k -> new ArrayList<>()).add(c);
        }
        return out;
    }

    private static int samePlace(JsonNode label, JsonNode predicted) {
        Set<String> places = places(label);
        places.retainAll(places(predicted));
        return places.isEmpty() ? 0 : 1;
    }

    private static Set<String> places(JsonNode candidate) {
        Set<String> out = new HashSet<>();
        for (JsonNode ref : candidate.path("source_refs")) {
            if (ref.path("page").canConvertToInt()) {
                out.add("page:" + ref.path("page").asInt());
            }
            if (!absent(ref.get("sheet")) && ref.path("row").canConvertToInt()) {
                out.add("cell:" + ref.path("sheet").asText() + "!" + ref.path("row").asInt());
            }
        }
        return out;
    }

    private static int sameValues(JsonNode label, JsonNode predicted) {
        int same = 0;
        var fields = label.path("payload").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (equal(field.getValue(), predicted.path("payload").get(field.getKey()),
                    label.path("accept").get(field.getKey()))) {
                same++;
            }
        }
        return same;
    }
}

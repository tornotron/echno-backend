package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.tornotron.echno_backend.modules.inspections.api.ChecklistTemplatePort.TradeRef;

/**
 * Matches a quality requirement's activity, as printed, to one of the organization's inspection trades,
 * with no model. A trade matches when every word of its name (or of its code) appears in the activity, or
 * in the material when the activity is empty, after folding plurals and "-ing" forms; a few words that
 * tenders use for a catalogue trade under another name ("concrete" for RCC, "rebar" for reinforcement)
 * count as that trade. Only a single match is kept: two trades matching, or none, leave the trade empty
 * for the person to choose when copying.
 */
final class TradeMatcher {

    /** Words a specification prints for a catalogue trade, by the catalogue code. */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("rcc", List.of("concrete", "concreting", "rcc", "pcc", "reinforced cement concrete")),
            Map.entry("reinforcement", List.of("rebar", "tmt", "reinforcement", "reinforcing steel", "steel bar")),
            Map.entry("shuttering-formwork", List.of("formwork", "shuttering", "centering")),
            Map.entry("masonry", List.of("masonry", "brickwork", "brick work", "blockwork", "block work", "brick")),
            Map.entry("plastering", List.of("plaster", "plastering")),
            Map.entry("waterproofing", List.of("waterproofing", "water proofing")),
            Map.entry("painting", List.of("paint", "painting")),
            Map.entry("tiling", List.of("tile", "tiling")),
            Map.entry("fabrication", List.of("fabrication", "structural steel", "structure steel")));

    private static final Set<String> FILLER = Set.of("and", "of", "the", "for", "work", "works");

    private TradeMatcher() {
    }

    /** The one trade the text names, or empty when none or more than one does. */
    static Optional<TradeRef> match(String activity, String material, List<TradeRef> trades) {
        String text = activity != null && !activity.isBlank() ? activity : material;
        if (text == null || text.isBlank() || trades == null || trades.isEmpty()) {
            return Optional.empty();
        }
        Set<String> words = stems(text);
        String folded = String.join(" ", words);
        List<TradeRef> hits = new ArrayList<>();
        for (TradeRef t : trades) {
            if (names(t, words) || aliased(t, folded)) {
                hits.add(t);
            }
        }
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    private static boolean names(TradeRef t, Set<String> words) {
        for (String name : List.of(nullToEmpty(t.name()), nullToEmpty(t.code()).replace('-', ' '))) {
            Set<String> needed = stems(name);
            if (!needed.isEmpty() && words.containsAll(needed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean aliased(TradeRef t, String folded) {
        String code = t.catalogueCode() != null ? t.catalogueCode() : t.code();
        List<String> aliases = code == null ? null : ALIASES.get(code);
        if (aliases == null) {
            return false;
        }
        String padded = " " + folded + " ";
        for (String alias : aliases) {
            if (padded.contains(" " + String.join(" ", stems(alias)) + " ")) {
                return true;
            }
        }
        return false;
    }

    /** Lower-cased words with plurals and "-ing" folded, filler words dropped, in order. */
    static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.isEmpty() || FILLER.contains(w)) {
                continue;
            }
            out.add(stem(w));
        }
        return out;
    }

    private static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) {
            return w.substring(0, w.length() - 3);
        }
        if (w.length() > 4 && w.endsWith("es") && !w.endsWith("ses")) {
            return w.substring(0, w.length() - 1);
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

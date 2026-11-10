package org.tornotron.echno_backend.modules.documentingestion.extraction.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the standards a line of a specification cites, with no model: Indian Standards in the forms
 * tenders print them ({@code IS 456:2000}, {@code IS:8112:1989}, {@code IS1786:2008},
 * {@code IS 2386 (Part 3)-1963}), road codes ({@code IRC:SP:73-2018}) and the common foreign ones
 * ({@code BS EN 197-1}, {@code ASTM C150}, {@code ISO 9001:2015}). Each is kept as printed, so a reviewer
 * reads what the page says; two spellings of one code are one standard.
 */
public final class Standards {

    private static final Pattern CODE = Pattern.compile(
            "(?<![A-Za-z])(?:IS|IRC|BS\\s?EN|BS|EN|ASTM|ISO|IEC|ACI)"
                    + "(?:\\s*[:/-]\\s*|\\s+)?"
                    + "(?:(?:SP|ISO|IEC)\\s*[:/-]?\\s*)?"
                    + "[A-Z]?\\d{1,5}(?![\\d])"
                    + "(?:\\s*\\(\\s*(?:Part|Pt\\.?|P)\\s*[0-9IVX]+(?:\\s*/\\s*Sec(?:tion)?\\s*\\d+)?\\s*\\))?"
                    + "(?:\\s*[:-]\\s*(?:19|20)\\d{2}(?![\\d]))?");

    private Standards() {
    }

    /** The standards cited in the text, in the order printed, each once. */
    public static List<String> find(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, String> byFolded = new LinkedHashMap<>();
        Matcher m = CODE.matcher(text);
        while (m.find()) {
            String printed = m.group().strip().replaceAll("\\s+", " ");
            byFolded.putIfAbsent(fold(printed), printed);
        }
        return new ArrayList<>(byFolded.values());
    }

    /** Whether the text reads as one standard code and nothing more. */
    public static boolean isCode(String text) {
        if (text == null) {
            return false;
        }
        Matcher m = CODE.matcher(text.strip());
        return m.matches();
    }

    /** Case, spaces and the punctuation between parts folded away, so {@code IS:456-2000} is {@code IS 456:2000}. */
    static String fold(String code) {
        return code.toUpperCase(Locale.ROOT).replaceAll("[\\s:/\\-().]", "").replace("PART", "P").replace("PT", "P");
    }
}

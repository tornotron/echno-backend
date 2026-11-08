package org.tornotron.echno_backend.modules.documentingestion.extraction.quote;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 3 of the quote check: every number and date in a value must appear in its quote, or the value
 * is dropped. Numbers compare as exact decimals, so {@code 1,23,456.00} in the quote supports
 * {@code 123456} in the value and {@code 123465} is refused. A date's day, month and year must each
 * appear, as digits or as a month name, so {@code 2026-11-30} is supported by {@code 30.11.2026} and by
 * {@code 30th November 2026}.
 */
public final class QuotedNumbers {

    private static final Pattern DATE = Pattern.compile("(?<![\\d.])(\\d{1,4})[./-](\\d{1,2})[./-](\\d{1,4})(?![\\d])");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4), Map.entry("may", 5),
            Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9), Map.entry("oct", 10),
            Map.entry("nov", 11), Map.entry("dec", 12));

    private QuotedNumbers() {
    }

    /** The numbers of the value that its quote does not hold; empty when the value is supported. */
    public static List<String> missing(Object value, String quote) {
        Set<String> inQuote = numbersOf(quote == null ? "" : quote);
        List<String> missing = new ArrayList<>();
        for (String needed : needed(value)) {
            if (!inQuote.contains(needed)) {
                missing.add(needed);
            }
        }
        return missing;
    }

    static List<String> needed(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null || value instanceof Boolean) {
            return out;
        }
        if (value instanceof Number n) {
            out.add(canonical(new BigDecimal(n.toString())));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> out.addAll(needed(v)));
            return out;
        }
        if (value instanceof Collection<?> list) {
            list.forEach(v -> out.addAll(needed(v)));
            return out;
        }
        String s = value.toString();
        Matcher date = DATE.matcher(s);
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (date.find()) {
            for (int g = 1; g <= 3; g++) {
                out.add(canonical(new BigDecimal(date.group(g))));
            }
            rest.append(s, last, date.start()).append(' ');
            last = date.end();
        }
        rest.append(s.substring(last));
        Matcher number = NUMBER.matcher(rest);
        while (number.find()) {
            out.add(canonical(new BigDecimal(number.group().replace(",", ""))));
        }
        return out;
    }

    static Set<String> numbersOf(String quote) {
        Set<String> out = new HashSet<>();
        Matcher number = NUMBER.matcher(quote);
        while (number.find()) {
            String token = number.group();
            out.add(canonical(new BigDecimal(token.replace(",", ""))));
            for (String part : token.split("[.,]")) {
                if (!part.isEmpty()) {
                    out.add(canonical(new BigDecimal(part)));
                }
            }
        }
        String lower = quote.toLowerCase(Locale.ROOT);
        Matcher words = Pattern.compile("\\p{L}+").matcher(lower);
        while (words.find()) {
            String w = words.group();
            if (w.length() >= 3 && MONTHS.containsKey(w.substring(0, 3)) && isMonthWord(w)) {
                out.add(Integer.toString(MONTHS.get(w.substring(0, 3))));
            }
        }
        return out;
    }

    private static boolean isMonthWord(String w) {
        return w.length() == 3 || List.of("january", "february", "march", "april", "june", "july", "august",
                "september", "sept", "october", "november", "december").contains(w);
    }

    private static String canonical(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
}

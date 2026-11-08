package org.tornotron.echno_backend.modules.documentingestion.extraction.quote;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

/**
 * The main defence against a model inventing values (design section 8.3). The model must name a page
 * and quote it; the page must be one of the chunk's, and the quote must be on it. Whitespace, case and
 * typographic quotes and dashes are normalized on both sides. On an OCR page a small edit distance is
 * allowed, since the OCR text and the model's reading of the same words can differ by a letter. The
 * box is the union of the boxes of the lines the quote covers, so a verified value points at a real
 * place on the page.
 */
public final class QuoteCheck {

    /** Quotes shorter than this must match exactly even on an OCR page. */
    static final int MIN_FUZZY_LENGTH = 8;
    /** One edit per this many characters of quote, on an OCR page. */
    static final int CHARS_PER_EDIT = 10;
    /** A longer quote is refused: it is a copy of the page, not a citation. */
    static final int MAX_QUOTE_LENGTH = 1000;

    private QuoteCheck() {
    }

    /**
     * The result of one check.
     *
     * @param verified Whether the page is in the chunk and the quote is on it
     * @param box      Normalized {@code [x0, y0, x1, y1]} of the quote's lines, or null
     * @param ocr      Whether the page was read by OCR
     * @param failure  One plain sentence when not verified, else null
     */
    public record Match(boolean verified, List<Double> box, boolean ocr, String failure) {
    }

    public static Match check(ChunkPages chunk, Integer page, String quote) {
        if (page == null || !chunk.contains(page)) {
            return new Match(false, null, false, page == null
                    ? "No page was given for this value."
                    : "Page " + page + " was not among the pages sent, so the value cannot be checked.");
        }
        PageText text = chunk.page(page).orElseThrow();
        if (quote == null || quote.isBlank()) {
            return new Match(false, null, text.isOcr(), "No quote was given for this value on page " + page + ".");
        }
        String needle = normalize(quote);
        if (needle.length() > MAX_QUOTE_LENGTH) {
            return new Match(false, null, text.isOcr(), "The quote is too long to be a citation.");
        }
        Haystack hay = Haystack.of(text);
        int start = hay.text.indexOf(needle);
        int end = start < 0 ? -1 : start + needle.length();
        if (start < 0 && text.isOcr() && needle.length() >= MIN_FUZZY_LENGTH) {
            int[] found = approximate(needle, hay.text, Math.max(1, needle.length() / CHARS_PER_EDIT));
            if (found != null) {
                start = found[0];
                end = found[1];
            }
        }
        if (start < 0) {
            return new Match(false, null, text.isOcr(), "The quote was not found on page " + page + ".");
        }
        return new Match(true, hay.box(start, end), text.isOcr(), null);
    }

    /** NFKC, typographic quotes and dashes made plain, whitespace collapsed, lower case. */
    public static String normalize(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-').replace('\u00A0', ' ');
        return s.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The best approximate occurrence of {@code needle} in {@code hay} within {@code maxEdits}, as
     * {@code [start, end)}, or null. Sellers' algorithm: an edit distance where the match may start
     * anywhere in the text.
     */
    static int[] approximate(String needle, String hay, int maxEdits) {
        int m = needle.length();
        int n = hay.length();
        int[] prev = new int[n + 1];
        int[] prevStart = new int[n + 1];
        int[] cur = new int[n + 1];
        int[] curStart = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = 0;
            prevStart[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            curStart[0] = 0;
            char c = needle.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int sub = prev[j - 1] + (c == hay.charAt(j - 1) ? 0 : 1);
                int del = prev[j] + 1;
                int ins = cur[j - 1] + 1;
                if (sub <= del && sub <= ins) {
                    cur[j] = sub;
                    curStart[j] = prevStart[j - 1];
                } else if (del <= ins) {
                    cur[j] = del;
                    curStart[j] = prevStart[j];
                } else {
                    cur[j] = ins;
                    curStart[j] = curStart[j - 1];
                }
            }
            int[] t = prev;
            prev = cur;
            cur = t;
            t = prevStart;
            prevStart = curStart;
            curStart = t;
        }
        int best = -1;
        for (int j = 1; j <= n; j++) {
            if (prev[j] <= maxEdits && (best < 0 || prev[j] < prev[best])) {
                best = j;
            }
        }
        return best < 0 ? null : new int[] {prevStart[best], best};
    }

    /** A page's lines joined into one normalized string, with where each line starts. */
    private record Haystack(String text, List<int[]> spans, List<List<Double>> boxes) {

        static Haystack of(PageText page) {
            StringBuilder sb = new StringBuilder();
            List<int[]> spans = new ArrayList<>();
            List<List<Double>> boxes = new ArrayList<>();
            for (PageText.Line line : page.lines()) {
                String t = normalize(line.text());
                if (t.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                spans.add(new int[] {sb.length(), sb.length() + t.length()});
                boxes.add(line.box());
                sb.append(t);
            }
            return new Haystack(sb.toString(), spans, boxes);
        }

        List<Double> box(int start, int end) {
            double x0 = Double.MAX_VALUE;
            double y0 = Double.MAX_VALUE;
            double x1 = -1;
            double y1 = -1;
            for (int i = 0; i < spans.size(); i++) {
                int[] span = spans.get(i);
                List<Double> b = boxes.get(i);
                if (b == null || b.size() != 4 || span[1] <= start || span[0] >= end) {
                    continue;
                }
                x0 = Math.min(x0, b.get(0));
                y0 = Math.min(y0, b.get(1));
                x1 = Math.max(x1, b.get(2));
                y1 = Math.max(y1, b.get(3));
            }
            return x1 < 0 ? null : List.of(x0, y0, x1, y1);
        }
    }
}

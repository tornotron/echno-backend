package org.tornotron.echno_backend.modules.documentingestion.extraction.drawing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.tornotron.echno_backend.modules.documentingestion.extraction.values.TenderValues;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TitleBlock;

/**
 * Reads the fields of one title block from its words and their boxes, by patterns: a label such as
 * "DRAWING NO." or "SCALE", and its value to the right on the same row or in the cell below. A printed
 * revision table (a header row naming the revision, the date and a description) gives the revision
 * history, and its newest row stands in for a revision or date the block does not label. A discipline
 * the block does not label is read from a code in the number, such as {@code AR} in {@code RCB-AR-101}.
 *
 * <p>No guessing beyond that: a block with no drawing number found this way goes to the LLM, on the
 * crop's text only, under the quote check.
 */
public final class TitleBlockFields {

    /** One value found, with the box of its words on the page and the words as printed. */
    public record Found(Object value, List<Double> box, String quote) {
    }

    /** What the patterns found on one block; {@link #hasNumber()} says whether they succeeded. */
    public record Reading(Map<String, Found> fields) {

        public boolean hasNumber() {
            return fields.containsKey(DrawingFields.NUMBER);
        }
    }

    record Word(String text, double x0, double y0, double x1, double y1) {

        double cx() {
            return (x0 + x1) / 2;
        }

        double cy() {
            return (y0 + y1) / 2;
        }

        double h() {
            return Math.max(y1 - y0, 1e-4);
        }

        String norm() {
            return normalize(text);
        }
    }

    private record Label(String field, int from, int to) {
    }

    private static final Map<String, List<List<String>>> LABELS = labels();
    private static final Set<String> LABEL_WORDS = LABELS.values().stream().flatMap(List::stream)
            .flatMap(List::stream).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> HISTORY_DESCRIPTION = Set.of("DESCRIPTION", "DESC", "DETAILS", "REMARKS",
            "AMENDMENT", "AMENDMENTS", "PURPOSE", "NATURE", "CHANGE", "CHANGES");
    private static final Pattern REVISION_TOKEN = Pattern.compile("[A-Z]{1,2}|\\d{1,3}|[A-Z]{1,2}\\d{1,3}");
    private static final Pattern SINGLE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9./-]*");

    static final int MAX_TITLE_LENGTH = 300;
    static final int MAX_SHORT_LENGTH = 60;

    private TitleBlockFields() {
    }

    public static Reading read(TitleBlock block) {
        List<Word> words = words(block);
        if (words.isEmpty()) {
            return new Reading(Map.of());
        }
        double h = median(words.stream().map(Word::h).toList());
        List<List<Word>> rows = rows(words, h);
        Map<String, Found> fields = new LinkedHashMap<>();
        int header = -1;
        for (int r = 0; r < rows.size(); r++) {
            if (isHistoryHeader(rows.get(r))) {
                header = r;
                break;
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            if (r == header) {
                continue;
            }
            List<Word> row = rows.get(r);
            List<Label> labels = labels(row);
            for (int i = 0; i < labels.size(); i++) {
                Label label = labels.get(i);
                if (fields.containsKey(label.field())) {
                    continue;
                }
                Label next = i + 1 < labels.size() ? labels.get(i + 1) : null;
                List<Word> value = new ArrayList<>(row.subList(label.to(), next == null ? row.size() : next.from()));
                value.removeIf(w -> w.text().matches("[:\\-–#]+"));
                if (value.isEmpty()) {
                    double colStart = row.get(label.from()).x0() - 2 * h;
                    double colEnd = next == null ? Double.MAX_VALUE : row.get(next.from()).x0();
                    value = below(rows, r, header, colStart, colEnd, h,
                            DrawingFields.TITLE.equals(label.field()) ? 3 : 1);
                }
                accept(label.field(), value).ifPresent(f -> fields.put(label.field(), f));
            }
        }
        List<Map<String, Object>> history = new ArrayList<>();
        Found historySource = header < 0 ? null : history(rows, header, h, history);
        if (historySource != null) {
            fields.put(DrawingFields.HISTORY, new Found(history, historySource.box(), historySource.quote()));
            Map<String, Object> newest = history.get(history.size() - 1);
            if (!fields.containsKey(DrawingFields.REVISION) && newest.get(DrawingFields.HISTORY_REVISION) != null) {
                fields.put(DrawingFields.REVISION, new Found(newest.get(DrawingFields.HISTORY_REVISION),
                        historySource.box(), newestQuote(historySource)));
            }
            if (!fields.containsKey(DrawingFields.DATE) && newest.get(DrawingFields.HISTORY_DATE) != null) {
                fields.put(DrawingFields.DATE, new Found(newest.get(DrawingFields.HISTORY_DATE), historySource.box(),
                        newestQuote(historySource)));
            }
        }
        Found number = fields.get(DrawingFields.NUMBER);
        if (number != null && !fields.containsKey(DrawingFields.DISCIPLINE)) {
            DrawingFields.disciplineFromNumber(number.value().toString())
                    .ifPresent(d -> fields.put(DrawingFields.DISCIPLINE, new Found(d, number.box(), number.quote())));
        }
        return new Reading(fields);
    }

    /** The words of the block, with a word that glues a label to its value ("SCALE:1:100") split in two. */
    static List<Word> words(TitleBlock block) {
        List<PageText.Line> source = block.words().isEmpty() ? splitLines(block.lines()) : block.words();
        List<Word> out = new ArrayList<>();
        for (PageText.Line w : source) {
            if (w.box() == null || w.text().isBlank()) {
                continue;
            }
            Word word = new Word(w.text().strip(), w.box().get(0), w.box().get(1), w.box().get(2), w.box().get(3));
            int colon = word.text().indexOf(':');
            if (colon > 0 && colon < word.text().length() - 1
                    && LABEL_WORDS.contains(normalize(word.text().substring(0, colon)))) {
                double split = word.x0() + (word.x1() - word.x0()) * (colon + 1) / word.text().length();
                out.add(new Word(word.text().substring(0, colon + 1), word.x0(), word.y0(), split, word.y1()));
                out.add(new Word(word.text().substring(colon + 1), split, word.y0(), word.x1(), word.y1()));
            } else {
                out.add(word);
            }
        }
        return out;
    }

    /** Lines cut into words, each with its share of the line's box by characters. */
    private static List<PageText.Line> splitLines(List<PageText.Line> lines) {
        List<PageText.Line> out = new ArrayList<>();
        for (PageText.Line line : lines) {
            if (line.box() == null) {
                continue;
            }
            String text = line.text();
            double x0 = line.box().get(0);
            double width = line.box().get(2) - x0;
            int at = 0;
            for (String token : text.split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                int start = text.indexOf(token, at);
                at = start + token.length();
                out.add(new PageText.Line(token, List.of(x0 + width * start / text.length(), line.box().get(1),
                        x0 + width * at / text.length(), line.box().get(3)), line.ocrConfidence()));
            }
        }
        return out;
    }

    static List<List<Word>> rows(List<Word> words, double h) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(Word::cy).thenComparingDouble(Word::x0));
        List<List<Word>> rows = new ArrayList<>();
        List<Word> row = new ArrayList<>();
        double rowCy = 0;
        for (Word w : sorted) {
            if (!row.isEmpty() && Math.abs(w.cy() - rowCy) > 0.4 * h) {
                rows.add(row);
                row = new ArrayList<>();
            }
            row.add(w);
            rowCy = row.stream().mapToDouble(Word::cy).average().orElse(w.cy());
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.forEach(r -> r.sort(Comparator.comparingDouble(Word::x0)));
        return rows;
    }

    private static List<Label> labels(List<Word> row) {
        List<Label> out = new ArrayList<>();
        int i = 0;
        while (i < row.size()) {
            Label found = labelAt(row, i);
            if (found == null) {
                i++;
            } else {
                out.add(found);
                i = found.to();
            }
        }
        return out;
    }

    private static Label labelAt(List<Word> row, int i) {
        Label best = null;
        for (Map.Entry<String, List<List<String>>> e : LABELS.entrySet()) {
            for (List<String> seq : e.getValue()) {
                if (i + seq.size() > row.size() || (best != null && seq.size() <= best.to() - best.from())) {
                    continue;
                }
                boolean match = true;
                for (int k = 0; k < seq.size() && match; k++) {
                    match = seq.get(k).equals(row.get(i + k).norm());
                }
                if (match) {
                    best = new Label(e.getKey(), i, i + seq.size());
                }
            }
        }
        return best;
    }

    private static boolean startsWithLabel(List<Word> words) {
        return !words.isEmpty() && labelAt(words, 0) != null;
    }

    /** The words in the column below a label, from up to {@code maxRows} rows that hold no label of their own. */
    private static List<Word> below(List<List<Word>> rows, int r, int header, double colStart, double colEnd, double h,
                                    int maxRows) {
        List<Word> out = new ArrayList<>();
        double lastCy = rows.get(r).get(0).cy();
        for (int k = r + 1; k < rows.size() && maxRows > 0; k++) {
            if (k == header) {
                break;
            }
            List<Word> inColumn = rows.get(k).stream().filter(w -> w.cx() >= colStart && w.cx() < colEnd).toList();
            if (inColumn.isEmpty()) {
                continue;
            }
            double cy = inColumn.get(0).cy();
            if (cy - lastCy > (out.isEmpty() ? 4 : 2.5) * h || startsWithLabel(inColumn)) {
                break;
            }
            List<Word> taken = new ArrayList<>();
            for (int i = 0; i < inColumn.size(); i++) {
                if (labelAt(inColumn, i) != null) {
                    break;
                }
                taken.add(inColumn.get(i));
            }
            out.addAll(taken);
            lastCy = cy;
            maxRows--;
            if (taken.size() < inColumn.size()) {
                break;
            }
        }
        return out;
    }

    /** The value of a field, if the words read as one. */
    private static Optional<Found> accept(String field, List<Word> words) {
        if (words.isEmpty()) {
            return Optional.empty();
        }
        String text = join(words);
        List<Double> box = union(words);
        String first = words.get(0).text();
        return switch (field) {
            case DrawingFields.NUMBER -> {
                if (DrawingFields.looksLikeNumber(text) && !text.contains(" ")) {
                    yield Optional.of(new Found(text, box, text));
                }
                if (DrawingFields.looksLikeNumber(first)) {
                    yield Optional.of(new Found(first, union(words.subList(0, 1)), first));
                }
                // A number printed with spaces, such as "RCB AR 101".
                yield DrawingFields.looksLikeNumber(text) && words.size() <= 4
                        ? Optional.of(new Found(text, box, text)) : Optional.empty();
            }
            case DrawingFields.REVISION -> {
                String r = SINGLE_TOKEN.matcher(text).matches() && text.length() <= DrawingFields.MAX_REVISION_LENGTH
                        ? text : SINGLE_TOKEN.matcher(first).matches() && first.length() <= DrawingFields.MAX_REVISION_LENGTH
                        ? first : null;
                yield r == null ? Optional.empty()
                        : Optional.of(new Found(r, r.equals(text) ? box : union(words.subList(0, 1)), r));
            }
            case DrawingFields.TITLE -> text.length() <= MAX_TITLE_LENGTH
                    ? Optional.of(new Found(text, box, text)) : Optional.empty();
            case DrawingFields.DATE -> text.length() <= MAX_SHORT_LENGTH
                    && (TenderValues.date(text).isPresent() || text.chars().anyMatch(Character::isDigit))
                    ? Optional.of(new Found(text, box, text)) : Optional.empty();
            default -> text.length() <= MAX_SHORT_LENGTH ? Optional.of(new Found(text, box, text)) : Optional.empty();
        };
    }

    private static boolean isHistoryHeader(List<Word> row) {
        Set<String> norm = row.stream().map(Word::norm).collect(Collectors.toSet());
        return (norm.contains("REV") || norm.contains("REVISION")) && norm.contains("DATE")
                && norm.stream().anyMatch(HISTORY_DESCRIPTION::contains);
    }

    /**
     * Reads the rows of a printed revision table into {@code out}, oldest first.
     *
     * @return the box and text of the rows, or null when the table has no row that reads
     */
    private static Found history(List<List<Word>> rows, int header, double h, List<Map<String, Object>> out) {
        List<Word> head = rows.get(header);
        List<double[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Word w : head) {
            String n = w.norm();
            String col = n.equals("REV") || n.equals("REVISION") ? DrawingFields.HISTORY_REVISION
                    : n.equals("DATE") ? DrawingFields.HISTORY_DATE
                    : HISTORY_DESCRIPTION.contains(n) ? DrawingFields.HISTORY_DESCRIPTION : "other";
            if (!names.isEmpty() && names.get(names.size() - 1).equals(col)) {
                continue;
            }
            names.add(col);
            starts.add(new double[] {w.x0() - 0.5 * h});
        }
        record Row(String revision, String date, String description, List<Word> words, int index) {
        }
        List<Row> read = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            if (r == header) {
                continue;
            }
            Map<String, List<Word>> cols = new LinkedHashMap<>();
            for (Word w : rows.get(r)) {
                int col = 0;
                for (int c = 0; c < starts.size(); c++) {
                    if (starts.get(c)[0] <= w.cx()) {
                        col = c;
                    }
                }
                cols.computeIfAbsent(names.get(col), k -> new ArrayList<>()).add(w);
            }
            String revision = cols.containsKey(DrawingFields.HISTORY_REVISION)
                    ? join(cols.get(DrawingFields.HISTORY_REVISION)) : null;
            String date = cols.containsKey(DrawingFields.HISTORY_DATE) ? join(cols.get(DrawingFields.HISTORY_DATE)) : null;
            if (revision == null || !REVISION_TOKEN.matcher(revision.toUpperCase(Locale.ROOT)).matches()
                    || date == null || TenderValues.date(date).isEmpty()) {
                continue;
            }
            String description = cols.containsKey(DrawingFields.HISTORY_DESCRIPTION)
                    ? join(cols.get(DrawingFields.HISTORY_DESCRIPTION)) : null;
            read.add(new Row(revision, date, description, rows.get(r), r));
        }
        if (read.isEmpty()) {
            return null;
        }
        read.sort((a, b) -> RevisionOrder.compare(a.revision(), TenderValues.date(a.date()).orElse(null), 0,
                b.revision(), TenderValues.date(b.date()).orElse(null), 0));
        List<Word> all = new ArrayList<>();
        List<String> quotes = new ArrayList<>();
        for (Row row : read) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(DrawingFields.HISTORY_REVISION, row.revision());
            m.put(DrawingFields.HISTORY_DATE, row.date());
            m.put(DrawingFields.HISTORY_DESCRIPTION, row.description());
            out.add(m);
            all.addAll(row.words());
            quotes.add(join(row.words()));
        }
        return new Found(null, union(all), String.join("\n", quotes));
    }

    private static String newestQuote(Found history) {
        String q = history.quote();
        return q.substring(q.lastIndexOf('\n') + 1);
    }

    static String normalize(String text) {
        String n = text.toUpperCase(Locale.ROOT).replaceAll("[.:#]", "").replaceAll("-+$", "").strip();
        return n.equals("N0") ? "NO" : n;
    }

    private static String join(List<Word> words) {
        return words.stream().map(Word::text).collect(Collectors.joining(" ")).strip();
    }

    private static List<Double> union(List<Word> words) {
        return List.of(words.stream().mapToDouble(Word::x0).min().orElse(0),
                words.stream().mapToDouble(Word::y0).min().orElse(0),
                words.stream().mapToDouble(Word::x1).max().orElse(0),
                words.stream().mapToDouble(Word::y1).max().orElse(0));
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static Map<String, List<List<String>>> labels() {
        Map<String, List<List<String>>> m = new LinkedHashMap<>();
        m.put(DrawingFields.NUMBER, List.of(List.of("DRAWING", "NO"), List.of("DRAWING", "NUMBER"),
                List.of("DRAWING", "NR"), List.of("DRG", "NO"), List.of("DWG", "NO"), List.of("DRG", "NUMBER"),
                List.of("DWG", "NUMBER"), List.of("DRGNO"), List.of("DWGNO"), List.of("DRAWINGNO")));
        m.put(DrawingFields.TITLE, List.of(List.of("DRAWING", "TITLE"), List.of("SHEET", "TITLE"),
                List.of("DRAWING", "NAME"), List.of("TITLE")));
        m.put(DrawingFields.REVISION, List.of(List.of("REVISION", "NO"), List.of("REV", "NO"), List.of("REVISION"),
                List.of("REV")));
        m.put(DrawingFields.DATE, List.of(List.of("DATE", "OF", "ISSUE"), List.of("ISSUE", "DATE"), List.of("DATE")));
        m.put(DrawingFields.SCALE, List.of(List.of("SCALE")));
        m.put(DrawingFields.DISCIPLINE, List.of(List.of("DISCIPLINE")));
        return m;
    }
}

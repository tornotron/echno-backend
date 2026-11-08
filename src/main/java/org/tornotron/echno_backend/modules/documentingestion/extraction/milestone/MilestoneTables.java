package org.tornotron.echno_backend.modules.documentingestion.extraction.milestone;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.extraction.values.TenderValues;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;

/**
 * Finds milestone tables among a document's table rows, a workbook's sheets or the tables of a PDF, and
 * reads each row with no LLM: a header row names the columns (milestone, time or date, percentage,
 * amount), and every row under it with a name is one milestone, with the cell each value came from. A
 * PDF table that runs on to the next page with no header of its own keeps the columns of the one before.
 */
public final class MilestoneTables {

    public static final String NAME = "name";
    public static final String TIME = "time";
    public static final String PERCENT = "percent";
    public static final String AMOUNT = "amount";
    public static final String ORDER = "order";

    public static final String PERCENT_TOTAL = "PERCENT_TOTAL";
    public static final String DATE_ORDER = "DATE_ORDER";
    public static final String RELATIVE_TIME = "RELATIVE_TIME";

    /** How far down a table its header row may sit. */
    static final int HEADER_SEARCH_ROWS = 10;
    static final int MAX_NAME_LENGTH = 255;

    private static final Pattern SERIAL = Pattern.compile("^(s\\.?\\s*no|sl|sr|serial|no\\.?|#|item\\s*no)\\b.*");
    private static final Pattern TOTAL = Pattern.compile("^(grand\\s+)?total\\b.*", Pattern.CASE_INSENSITIVE);

    private MilestoneTables() {
    }

    enum Column {
        NAME, TIME, PERCENT, AMOUNT
    }

    /** The columns of one table, by 1-based column number. */
    record Mapping(Map<Column, Integer> columns, int width) {
    }

    /**
     * The milestone candidates in the context's tables.
     *
     * @param needMilestoneWord Whether a header must say "milestone", for a part where milestone tables
     *                          are one table among many, such as contract data
     */
    public static List<ProposedCandidate> read(ExtractContext context, boolean needMilestoneWord) {
        List<List<Row>> logical = new ArrayList<>();
        Mapping carried = null;
        Integer carriedPage = null;
        for (List<TableRow> table : group(context.tables())) {
            TableRow first = table.get(0);
            int headerIndex = -1;
            Mapping mapping = null;
            for (int i = 0; i < Math.min(HEADER_SEARCH_ROWS, table.size()); i++) {
                Optional<Mapping> m = header(table.get(i), needMilestoneWord);
                if (m.isPresent()) {
                    headerIndex = i;
                    mapping = m.get();
                    break;
                }
            }
            boolean continuation = mapping == null && carried != null && first.page() != null && carriedPage != null
                    && first.page() == carriedPage + 1 && width(first) == carried.width();
            if (mapping == null && !continuation) {
                carried = null;
                continue;
            }
            List<Row> rows;
            if (continuation) {
                mapping = carried;
                rows = logical.get(logical.size() - 1);
            } else {
                rows = new ArrayList<>();
                logical.add(rows);
            }
            for (int i = headerIndex + 1; i < table.size(); i++) {
                Row r = row(table.get(i), mapping);
                if (r != null) {
                    rows.add(r);
                }
            }
            carried = mapping;
            carriedPage = table.get(table.size() - 1).page();
        }
        List<ProposedCandidate> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int order = 0;
        for (List<Row> rows : logical) {
            List<CandidateFlag> tableFlags = new ArrayList<>();
            percentTotal(rows.stream().map(r -> r.values.get(PERCENT)).toList()).ifPresent(tableFlags::add);
            Integer lastDay = null;
            for (Row r : rows) {
                List<CandidateFlag> flags = new ArrayList<>(tableFlags);
                Integer day = sortDay(r.values.get(TIME));
                if (day != null && lastDay != null && day < lastDay) {
                    flags.add(new CandidateFlag(DATE_ORDER, "This milestone falls before the one above it."));
                }
                if (day != null) {
                    lastDay = day;
                }
                relativeFlag(r.values.get(TIME)).ifPresent(flags::add);
                Map<String, Object> payload = new LinkedHashMap<>(r.values);
                payload.put(ORDER, order++);
                String key = uniqueKey(keys, r.values.get(NAME), r.label);
                out.add(new ProposedCandidate(context.packageId(), context.documentId(), context.segmentId(),
                        CandidateKind.MILESTONE, key, payload, flags, ExtractionMethod.DETERMINISTIC,
                        sources(context, r), null));
            }
        }
        return out;
    }

    /** {@value #PERCENT_TOTAL} when the percentages given do not add up to 100. */
    public static Optional<CandidateFlag> percentTotal(List<?> percents) {
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (Object p : percents) {
            Optional<BigDecimal> v = p == null ? Optional.empty() : TenderValues.percentOrNumber(p.toString());
            if (v.isPresent()) {
                total = total.add(v.get());
                any = true;
            }
        }
        if (!any || total.compareTo(BigDecimal.valueOf(100)) == 0) {
            return Optional.empty();
        }
        return Optional.of(new CandidateFlag(PERCENT_TOTAL, "The payment percentages of this schedule add up to "
                + total.stripTrailingZeros().toPlainString() + ", not 100."));
    }

    /** {@value #RELATIVE_TIME} when the time is counted from the start, as "Month 4" is. */
    public static Optional<CandidateFlag> relativeFlag(Object time) {
        if (time == null || TenderValues.date(time.toString()).isPresent()
                || TenderValues.span(time.toString()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CandidateFlag(RELATIVE_TIME, "\"" + time + "\" is counted from the project's start "
                + "date when this is applied. With no start date the date is left empty."));
    }

    /** A percentage printed in an amount column, such as "1.25% of the tendered value", is a percentage. */
    public static void percentFromAmount(Map<String, Object> values) {
        Object amount = values.get(AMOUNT);
        if (values.get(PERCENT) == null && amount != null && TenderValues.percent(amount.toString()).isPresent()) {
            values.put(PERCENT, amount);
            values.remove(AMOUNT);
        }
    }

    private static Integer sortDay(Object time) {
        if (time == null) {
            return null;
        }
        Optional<java.time.LocalDate> date = TenderValues.date(time.toString());
        if (date.isPresent()) {
            return (int) date.get().toEpochDay();
        }
        return TenderValues.span(time.toString()).map(TenderValues.Span::approximateDays).orElse(null);
    }

    private static Optional<Mapping> header(TableRow row, boolean needMilestoneWord) {
        Map<Column, Integer> columns = new LinkedHashMap<>();
        StringBuilder all = new StringBuilder();
        for (TableRow.Cell cell : row.cells()) {
            String h = cell.shownText().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
            if (h.isEmpty()) {
                continue;
            }
            all.append(h).append(' ');
            if (SERIAL.matcher(h).matches()) {
                continue;
            }
            Column c = classify(h);
            if (c != null) {
                columns.putIfAbsent(c, cell.col());
            }
        }
        boolean enough = columns.containsKey(Column.NAME) && columns.size() >= 2;
        if (!enough || (needMilestoneWord && !all.toString().contains("milestone"))) {
            return Optional.empty();
        }
        return Optional.of(new Mapping(columns, width(row)));
    }

    static Column classify(String h) {
        if (h.contains("%") || h.contains("percent")) {
            return Column.PERCENT;
        }
        if (containsAny(h, "date", "time", "month", "period", "days", "schedule", "due", "target", "week")) {
            return Column.TIME;
        }
        if (containsAny(h, "amount", "value", "payment", "withheld", "sum", "rs.", "inr")) {
            return Column.AMOUNT;
        }
        if (containsAny(h, "milestone", "description", "stage", "activity", "event", "particular", "work")) {
            return Column.NAME;
        }
        return null;
    }

    private static boolean containsAny(String h, String... words) {
        for (String w : words) {
            if (h.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** One milestone row: its values as printed and the cell each came from. */
    private record Row(Map<String, Object> values, Map<String, TableRow.Cell> cells, TableRow row, String label) {
    }

    private static Row row(TableRow row, Mapping mapping) {
        TableRow.Cell nameCell = row.cell(mapping.columns().get(Column.NAME));
        String name = nameCell == null ? "" : nameCell.shownText().replaceAll("\\s+", " ").strip();
        if (name.isEmpty() || TOTAL.matcher(name).matches()) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, TableRow.Cell> cells = new LinkedHashMap<>();
        values.put(NAME, name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH));
        cells.put(NAME, nameCell);
        put(values, cells, TIME, row.cell(colOrZero(mapping, Column.TIME)));
        put(values, cells, PERCENT, row.cell(colOrZero(mapping, Column.PERCENT)));
        put(values, cells, AMOUNT, row.cell(colOrZero(mapping, Column.AMOUNT)));
        if (values.get(PERCENT) == null && values.get(AMOUNT) != null
                && TenderValues.percent(values.get(AMOUNT).toString()).isPresent()) {
            values.put(PERCENT, values.remove(AMOUNT));
            cells.put(PERCENT, cells.remove(AMOUNT));
        }
        String label = row.sheet() != null ? row.sheet() + " row " + row.row() : "page " + row.page() + " row " + row.row();
        return new Row(values, cells, row, label);
    }

    private static int colOrZero(Mapping mapping, Column c) {
        return mapping.columns().getOrDefault(c, 0);
    }

    private static void put(Map<String, Object> values, Map<String, TableRow.Cell> cells, String field,
                            TableRow.Cell cell) {
        if (cell == null || cell.isEmpty()) {
            return;
        }
        String text = "DATE".equals(cell.type()) && cell.value() != null && cell.value().isTextual()
                ? cell.value().asText().substring(0, Math.min(10, cell.value().asText().length()))
                : cell.shownText();
        if (!text.isBlank()) {
            values.put(field, text.strip());
            cells.put(field, cell);
        }
    }

    private static List<ProposedCandidate.ProposedSource> sources(ExtractContext context, Row r) {
        List<ProposedCandidate.ProposedSource> out = new ArrayList<>();
        r.cells().forEach((field, cell) -> out.add(new ProposedCandidate.ProposedSource(field, context.documentId(),
                context.documentSha256(), r.row().page(), cell.box(), r.row().sheet(),
                r.row().sheet() != null ? r.row().row() : null, r.row().sheet() != null ? cell.ref() : null, null,
                cell.shownText(), true)));
        return out;
    }

    private static String uniqueKey(Set<String> keys, Object name, String label) {
        String key = String.valueOf(name);
        if (!keys.add(key)) {
            key = key + " (" + label + ")";
            keys.add(key);
        }
        return key;
    }

    private static int width(TableRow row) {
        return row.cells().stream().mapToInt(TableRow.Cell::col).max().orElse(0);
    }

    /** Rows grouped by sheet, or by page and table, in reading order. */
    private static List<List<TableRow>> group(List<TableRow> rows) {
        Map<String, List<TableRow>> byTable = new LinkedHashMap<>();
        for (TableRow r : rows) {
            if (r.hidden()) {
                continue;
            }
            String key = r.sheet() != null || r.sheetIndex() != null ? "s" + r.sheetIndex() + ":" + r.sheet()
                    : "p" + r.page() + ":" + r.table();
            byTable.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        return new ArrayList<>(byTable.values());
    }
}

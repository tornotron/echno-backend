package org.tornotron.echno_backend.modules.documentingestion.extraction.boq;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqSheetSummary;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqWarning;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow.Cell;

/**
 * Reads the rows of one BOQ sheet under a confirmed column mapping, deterministically and with no
 * model. Each row below the header becomes an item, a section heading, a total that is checked, or
 * an ignored row listed by number. Items carry the checks of section 4.2 of the design as flags a
 * reviewer reads, and each field keeps the cell it came from.
 */
public final class BoqSheetExtractor {

    public static final int MAX_ITEMS_PER_SHEET = 20_000;
    static final int MAX_IGNORED_LISTED = 500;
    static final int MAX_SECTION_LENGTH = 500;
    /** Target limits, from {@code BoqItemRequest}. */
    static final int MAX_CODE = 50;
    static final int MAX_UNIT = 30;
    static final int MAX_DESCRIPTION = 4000;
    /** Rupees of rounding a sheet may carry before quantity times rate counts as wrong. */
    static final BigDecimal AMOUNT_TOLERANCE = BigDecimal.ONE;

    private static final Pattern TOTAL = Pattern.compile(
            "^((grand|sub|section|page|net)\\s*-?\\s*)?total\\b.*|.*\\btotal$|^(carried|brought)\\s+(forward|over)\\b.*"
                    + "|^[cb]/f\\b.*");
    private static final Pattern GRAND_TOTAL = Pattern.compile("^grand\\s*total\\b.*");
    private static final Pattern FORWARD = Pattern.compile("^((carried|brought)\\s+(forward|over)|[cb]/f)\\b.*");
    private static final Pattern PROVISIONAL = Pattern.compile(
            "(?i).*\\b(provisional\\s+sum|prime\\s+cost|p\\.\\s?s\\.|p\\.\\s?c\\.\\s+sum)\\b.*|^p\\.?s\\.?\\b.*");

    private BoqSheetExtractor() {
    }

    /** The confirmed mapping. Field to 1-based column; {@code codePrefix} is put before every item code. */
    public record Mapping(int headerRow, Map<BoqField, Integer> columns, String codePrefix) {
    }

    /** The cell a field came from, as shown. */
    public record CellSource(String ref, String text) {
    }

    /**
     * One item. Numbers are as read, rounded to the target's scale where flagged; null where the
     * sheet gave no readable value.
     */
    public record Line(int row, String section, String itemCode, String description, String unit,
                       BigDecimal quantity, BigDecimal rate, BigDecimal amount, List<CandidateFlag> flags,
                       Map<BoqField, CellSource> sources) {
    }

    public record Result(List<Line> lines, BoqSheetSummary summary) {
    }

    public static Result extract(List<TableRow> sheetRows, boolean sheetHidden, Mapping mapping) {
        Map<String, String> textByRef = new HashMap<>();
        Map<String, Integer> firstRowOfCode = new HashMap<>();
        List<Line> lines = new ArrayList<>();
        List<Integer> ignored = new ArrayList<>();
        int ignoredCount = 0;
        List<BoqWarning> warnings = new ArrayList<>();
        int rowsRead = 0;
        int sections = 0;
        int totals = 0;
        String section = null;
        BigDecimal sectionSum = BigDecimal.ZERO;
        BigDecimal sheetSum = BigDecimal.ZERO;
        boolean itemsSinceTotal = false;

        for (TableRow row : sheetRows) {
            for (Cell c : row.cells()) {
                if (c.ref() != null && !c.isEmpty()) {
                    textByRef.put(c.ref(), c.shownText());
                }
            }
            if (row.row() <= mapping.headerRow()) {
                continue;
            }
            Map<BoqField, Cell> cells = new LinkedHashMap<>();
            Map<BoqField, CellSource> sources = new LinkedHashMap<>();
            Flags flags = new Flags();
            boolean anyMapped = false;
            for (Map.Entry<BoqField, Integer> e : mapping.columns().entrySet()) {
                Cell cell = row.cell(e.getValue());
                if (cell != null && cell.isEmpty() && cell.mergeOrigin() != null
                        && textByRef.containsKey(cell.mergeOrigin())) {
                    String origin = cell.mergeOrigin();
                    flags.add("MERGED_CELL", e.getKey().key() + " is taken from merged cell " + origin);
                    cell = new Cell(origin, cell.col(), null, "TEXT", textByRef.get(origin), false, false, null);
                }
                if (cell != null && !cell.isEmpty()) {
                    anyMapped = true;
                    cells.put(e.getKey(), cell);
                }
            }
            if (!anyMapped) {
                continue;
            }
            rowsRead++;
            if (BoqHeaderDetector.isHeaderLike(row) || isColumnNumberRow(cells.values())) {
                ignoredCount = ignore(ignored, ignoredCount, row.row());
                continue;
            }
            String code = text(cells.get(BoqField.ITEM_CODE));
            String description = text(cells.get(BoqField.DESCRIPTION));
            String unit = text(cells.get(BoqField.UNIT));
            BoqNumbers.Read qty = BoqNumbers.read(cells.get(BoqField.QUANTITY));
            BoqNumbers.Read rate = BoqNumbers.read(cells.get(BoqField.RATE));
            BoqNumbers.Read amount = BoqNumbers.read(cells.get(BoqField.AMOUNT));
            boolean numbers = qty.kind() != BoqNumbers.Kind.EMPTY || rate.kind() != BoqNumbers.Kind.EMPTY
                    || amount.kind() != BoqNumbers.Kind.EMPTY;
            String label = (description.isEmpty() ? code : description).toLowerCase(Locale.ROOT).strip();

            if (unit.isEmpty() && qty.kind() == BoqNumbers.Kind.EMPTY && TOTAL.matcher(label).matches()) {
                totals++;
                if (amount.hasValue() && !FORWARD.matcher(label).matches()) {
                    boolean grand = GRAND_TOTAL.matcher(label).matches();
                    BigDecimal expected = grand ? sheetSum : sectionSum;
                    if ((grand || itemsSinceTotal) && expected.subtract(amount.value()).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
                        warnings.add(new BoqWarning("SECTION_TOTAL_MISMATCH", "Row " + row.row() + " gives a total of "
                                + plain(amount.value()) + "; the items above it add up to " + plain(expected),
                                row.row()));
                    }
                }
                sectionSum = BigDecimal.ZERO;
                itemsSinceTotal = false;
                continue;
            }
            if (!numbers && unit.isEmpty()) {
                if (!description.isEmpty() || !code.isEmpty()) {
                    String heading = (code.isEmpty() || description.isEmpty() ? "" : code + " ") + label(description, code);
                    section = heading.length() > MAX_SECTION_LENGTH ? heading.substring(0, MAX_SECTION_LENGTH) : heading;
                    sections++;
                    sectionSum = BigDecimal.ZERO;
                    itemsSinceTotal = false;
                } else {
                    ignoredCount = ignore(ignored, ignoredCount, row.row());
                }
                continue;
            }
            if (description.isEmpty()) {
                ignoredCount = ignore(ignored, ignoredCount, row.row());
                continue;
            }
            if (lines.size() >= MAX_ITEMS_PER_SHEET) {
                warnings.add(new BoqWarning("ROWS_OVER_LIMIT", "The sheet has more than " + MAX_ITEMS_PER_SHEET
                        + " items; rows from " + row.row() + " on were not read. Split the sheet.", row.row()));
                break;
            }

            cells.forEach((field, cell) -> sources.put(field, new CellSource(cell.ref(), cell.shownText())));
            if (sheetHidden) {
                flags.add("HIDDEN_SHEET", "The row is on a hidden sheet");
            } else if (row.hidden()) {
                flags.add("HIDDEN_ROW", "The row is hidden in the workbook");
            }

            // Code
            String itemCode = code;
            if (itemCode.isEmpty()) {
                itemCode = "R" + row.row();
                flags.add("CODE_MISSING", "The row has no item code; " + itemCode + " is proposed from its row number");
            }
            if (mapping.codePrefix() != null && !mapping.codePrefix().isBlank()) {
                itemCode = mapping.codePrefix().strip() + itemCode;
            }
            Integer seenOn = firstRowOfCode.putIfAbsent(itemCode, row.row());
            if (seenOn != null) {
                flags.add("DUPLICATE_CODE", "Item code " + itemCode + " is also on row " + seenOn
                        + "; codes must be unique on a contract");
            }

            // Quantity, with lump-sum and provisional-sum lines proposed as 1 LS
            BigDecimal quantity = numberOrFlag(qty, "quantity", flags);
            boolean lumpSum = false;
            if (qty.kind() == BoqNumbers.Kind.LUMP_SUM) {
                lumpSum = true;
                flags.add("LUMP_SUM", "The quantity reads \"" + qty.text() + "\"; proposed as 1 LS");
            } else if (qty.kind() == BoqNumbers.Kind.EMPTY) {
                if (BoqNumbers.isLumpSumUnit(unit)) {
                    quantity = BigDecimal.ONE;
                    lumpSum = true;
                    flags.add("LUMP_SUM", "A lump-sum line with no quantity; proposed as 1 " + unit);
                } else if (PROVISIONAL.matcher(description).matches()) {
                    quantity = BigDecimal.ONE;
                    lumpSum = true;
                    flags.add("PROVISIONAL_SUM", "A provisional sum with no quantity; proposed as 1 LS");
                } else {
                    flags.add("QUANTITY_MISSING", "The row has no quantity; a contract BOQ line needs one above zero");
                }
            }
            if (lumpSum && unit.isEmpty()) {
                unit = "LS";
            }
            if (quantity != null && quantity.signum() <= 0) {
                flags.add("QUANTITY_MISSING", "The quantity is " + plain(quantity) + "; a contract BOQ line needs one above zero");
            }
            if (quantity != null && quantity.scale() > 3 && quantity.stripTrailingZeros().scale() > 3) {
                BigDecimal rounded = quantity.setScale(3, RoundingMode.HALF_UP);
                flags.add("PRECISION", "The quantity " + plain(quantity) + " lands as " + plain(rounded));
                quantity = rounded;
            }
            if (unit.isEmpty()) {
                flags.add("UNIT_MISSING", "The row has no unit");
            }

            // Rate; an unpriced BOQ lands at rate 0, which the contract BOQ allows
            BigDecimal rateValue = numberOrFlag(rate, "rate", flags);
            if (rate.kind() == BoqNumbers.Kind.EMPTY) {
                if (lumpSum && amount.hasValue() && quantity != null && quantity.compareTo(BigDecimal.ONE) == 0) {
                    rateValue = amount.value();
                    sources.put(BoqField.RATE, sources.get(BoqField.AMOUNT));
                } else {
                    rateValue = BigDecimal.ZERO;
                    flags.add("UNPRICED", "The row has no rate; it lands at rate 0");
                }
            } else if (rateValue != null && rateValue.signum() == 0) {
                flags.add("UNPRICED", "The rate is 0");
            }
            if (rateValue != null && rateValue.signum() < 0) {
                flags.add("NOT_A_NUMBER", "The rate is below zero");
            }
            if (rateValue != null && rateValue.stripTrailingZeros().scale() > 2) {
                BigDecimal rounded = rateValue.setScale(2, RoundingMode.HALF_UP);
                flags.add("PRECISION", "The rate " + plain(rateValue) + " lands as " + plain(rounded));
                rateValue = rounded;
            }

            // Amount: kept for the checks; the contract works its own out as quantity times rate
            BigDecimal amountValue = numberOrFlag(amount, "amount", flags);
            if (quantity != null && rateValue != null && amountValue != null) {
                BigDecimal expected = quantity.multiply(rateValue).setScale(2, RoundingMode.HALF_UP);
                if (expected.subtract(amountValue).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
                    flags.add("AMOUNT_MISMATCH", "Quantity times rate is " + plain(expected) + "; the sheet says "
                            + plain(amountValue));
                }
            }

            if (itemCode.length() > MAX_CODE) {
                flags.add("TOO_LONG", "The item code is longer than " + MAX_CODE + " characters");
            }
            if (unit.length() > MAX_UNIT) {
                flags.add("TOO_LONG", "The unit is longer than " + MAX_UNIT + " characters");
            }
            if (description.length() > MAX_DESCRIPTION) {
                flags.add("TOO_LONG", "The description is longer than " + MAX_DESCRIPTION + " characters");
            }

            BigDecimal lineAmount = amountValue != null ? amountValue
                    : quantity != null && rateValue != null ? quantity.multiply(rateValue) : BigDecimal.ZERO;
            sectionSum = sectionSum.add(lineAmount);
            sheetSum = sheetSum.add(lineAmount);
            itemsSinceTotal = true;
            lines.add(new Line(row.row(), section, itemCode, description, unit, quantity, rateValue, amountValue,
                    flags.list(), sources));
        }
        if (lines.isEmpty()) {
            warnings.add(new BoqWarning("NO_ITEMS", "No item rows were found under this mapping", null));
        }
        int flagged = (int) lines.stream().filter(l -> !l.flags().isEmpty()).count();
        if (ignoredCount > ignored.size()) {
            warnings.add(new BoqWarning("IGNORED_ROWS", (ignoredCount - ignored.size())
                    + " more ignored rows are not listed", null));
        }
        return new Result(lines, new BoqSheetSummary(rowsRead, lines.size(), sections, totals, flagged, ignored,
                warnings));
    }

    private static BigDecimal numberOrFlag(BoqNumbers.Read read, String field, Flags flags) {
        switch (read.kind()) {
            case NUMBER:
                return read.value();
            case TEXT_NUMBER:
                flags.add("NUMBER_AS_TEXT", "The " + field + " \"" + read.text() + "\" is a number held as text");
                return read.value();
            case CELL_ERROR:
                flags.add("CELL_ERROR", "The " + field + " cell shows the error " + read.text());
                return null;
            case NO_CACHED_VALUE:
                flags.add("FORMULA_NOT_CACHED", "The " + field + " is a formula the file holds no result for; "
                        + "formulas are not calculated");
                return null;
            case NOT_A_NUMBER:
                flags.add("NOT_A_NUMBER", "The " + field + " \"" + read.text() + "\" is not a number");
                return null;
            case LUMP_SUM:
                if (!"quantity".equals(field)) {
                    flags.add("NOT_A_NUMBER", "The " + field + " \"" + read.text() + "\" is not a number");
                    return null;
                }
                return read.value();
            default:
                return null;
        }
    }

    /** A row of small whole numbers counting up, such as "1 2 3 4 5 6" under a header. */
    private static boolean isColumnNumberRow(Iterable<Cell> cells) {
        int count = 0;
        BigDecimal previous = null;
        for (Cell c : cells) {
            if (!"NUMBER".equals(c.type()) || c.value() == null || !c.value().isNumber()) {
                return false;
            }
            BigDecimal v = c.value().decimalValue();
            if (v.stripTrailingZeros().scale() > 0 || (previous != null && v.compareTo(previous.add(BigDecimal.ONE)) != 0)) {
                return false;
            }
            previous = v;
            count++;
        }
        return count >= 3;
    }

    private static int ignore(List<Integer> ignored, int count, int row) {
        if (ignored.size() < MAX_IGNORED_LISTED) {
            ignored.add(row);
        }
        return count + 1;
    }

    private static String text(Cell cell) {
        return cell == null ? "" : cell.shownText();
    }

    private static String label(String description, String code) {
        return description.isEmpty() ? code : description;
    }

    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** One flag per code, with the messages of repeats joined. */
    private static final class Flags {
        private final Map<String, String> byCode = new LinkedHashMap<>();

        void add(String code, String message) {
            byCode.merge(code, message, (a, b) -> a.equals(b) ? a : a + "; " + b);
        }

        List<CandidateFlag> list() {
            List<CandidateFlag> out = new ArrayList<>();
            byCode.forEach((code, message) -> out.add(new CandidateFlag(code, message)));
            return out;
        }
    }
}

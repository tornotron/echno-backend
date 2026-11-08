package org.tornotron.echno_backend.modules.documentingestion.extraction.boq;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow.Cell;

/**
 * Reads a number from a cell the way a person reads the sheet. A NUMBER cell is taken as it is. A
 * TEXT cell holding a number, often in the Indian grouping ({@code 1,23,456.00}) or with a rupee
 * sign, is read and reported as text so the row is flagged. Formulas are never calculated: a formula
 * with no cached result reads as missing.
 */
final class BoqNumbers {

    enum Kind { EMPTY, NUMBER, TEXT_NUMBER, LUMP_SUM, CELL_ERROR, NO_CACHED_VALUE, NOT_A_NUMBER }

    record Read(Kind kind, BigDecimal value, String text) {

        boolean hasValue() {
            return value != null;
        }
    }

    private static final Set<String> ERRORS =
            Set.of("#DIV/0!", "#N/A", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#NULL!", "#SPILL!", "#CALC!");
    private static final Pattern LUMP_SUM = Pattern.compile(
            "^(1\\s*)?(l\\.?\\s*s\\.?|lump\\s*-?\\s*sum|lumpsum|job|1\\s*job|ls)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY = Pattern.compile("(?i)^(₹|rs\\.?|inr)\\s*|\\s*(/-|₹|rs\\.?|inr)$");
    private static final Pattern PLAIN = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    /** Western groups of three, or the Indian grouping: twos, then a final three. */
    private static final Pattern GROUPED = Pattern.compile(
            "^-?(\\d{1,3}(,\\d{3})+|\\d{1,2}(,\\d{2})*,\\d{3})(\\.\\d+)?$");

    private BoqNumbers() {
    }

    static Read read(Cell cell) {
        if (cell == null || cell.isEmpty()) {
            return new Read(Kind.EMPTY, null, "");
        }
        String text = cell.shownText();
        if (cell.noCachedValue()) {
            return new Read(Kind.NO_CACHED_VALUE, null, text);
        }
        if ("NUMBER".equals(cell.type()) && cell.value() != null && cell.value().isNumber()) {
            return new Read(Kind.NUMBER, cell.value().decimalValue(), text);
        }
        if (ERRORS.contains(text.toUpperCase(Locale.ROOT))) {
            return new Read(Kind.CELL_ERROR, null, text);
        }
        if (LUMP_SUM.matcher(text.strip()).matches()) {
            return new Read(Kind.LUMP_SUM, BigDecimal.ONE, text);
        }
        BigDecimal parsed = parseText(text);
        return parsed == null ? new Read(Kind.NOT_A_NUMBER, null, text) : new Read(Kind.TEXT_NUMBER, parsed, text);
    }

    /** A number written as text, with commas in either grouping and an optional rupee sign, or null. */
    static BigDecimal parseText(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().replace(' ', ' ');
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1).strip();
        }
        String previous;
        do {
            previous = s;
            s = CURRENCY.matcher(s).replaceAll("").strip();
        } while (!s.equals(previous));
        s = s.replace(" ", "");
        if (s.isEmpty()) {
            return null;
        }
        if (GROUPED.matcher(s).matches()) {
            s = s.replace(",", "");
        }
        if (!PLAIN.matcher(s).matches()) {
            return null;
        }
        BigDecimal value = new BigDecimal(s);
        return negative ? value.negate() : value;
    }

    static boolean isLumpSumUnit(String unit) {
        return unit != null && LUMP_SUM.matcher(unit.strip()).matches();
    }
}

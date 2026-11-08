package org.tornotron.echno_backend.modules.documentingestion.extraction.boq;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow.Cell;

/**
 * Finds the header row of a BOQ sheet by column synonyms and proposes which column holds which field.
 * No model is involved: the words BOQs use for their columns are few. A person confirms the mapping
 * before any row is read, so a wrong guess costs a correction, never a wrong value.
 */
public final class BoqHeaderDetector {

    /** Rows searched for a header, from the top of the sheet. */
    static final int SEARCH_ROWS = 40;
    /** Fields a header row must name to count as one. */
    static final int MIN_FIELDS = 3;

    private static final Set<String> CODE_WORDS = Set.of("item", "itemno", "itemnumber", "itemcode", "code", "slno",
            "sno", "srno", "serialno", "sino", "sl", "sr", "no", "ref", "refno", "itemref", "boqitem", "boqitemno",
            "boqref", "boqno", "sn", "sln", "itemsno", "itemslno");
    private static final Set<String> DESCRIPTION_WORDS = Set.of("particulars", "particular", "nameofitem",
            "itemofwork", "itemsofwork", "work", "specification", "nameofwork", "details", "descriptionofwork");
    private static final Set<String> UNIT_WORDS = Set.of("unit", "units", "uom", "unitofmeasurement",
            "unitofmeasure", "per", "um");
    private static final Set<String> AMOUNT_WORDS = Set.of("total", "totalamount", "value", "totalvalue", "cost",
            "totalcost", "totalrs", "totalinrs", "amountrs");

    private BoqHeaderDetector() {
    }

    /** The header found, or none. {@code complete} means a description and a quantity column were named. */
    public record Detection(int headerRow, Map<BoqField, Integer> columns, boolean complete) {
    }

    public static Optional<Detection> detect(List<TableRow> sheetRows) {
        Detection best = null;
        int searched = 0;
        for (TableRow row : sheetRows) {
            if (searched++ >= SEARCH_ROWS) {
                break;
            }
            Map<BoqField, Integer> columns = columnsOf(row);
            if (columns.size() < MIN_FIELDS || !columns.containsKey(BoqField.DESCRIPTION)) {
                continue;
            }
            if (best == null || columns.size() > best.columns().size()) {
                best = new Detection(row.row(), columns,
                        columns.containsKey(BoqField.DESCRIPTION) && columns.containsKey(BoqField.QUANTITY));
            }
        }
        return Optional.ofNullable(best);
    }

    /** The fields a row names, each at its left-most column. */
    static Map<BoqField, Integer> columnsOf(TableRow row) {
        Map<BoqField, Integer> columns = new EnumMap<>(BoqField.class);
        for (Cell cell : row.cells()) {
            if (cell.isEmpty() || !"TEXT".equals(cell.type())) {
                continue;
            }
            fieldOf(cell.shownText()).ifPresent(field -> columns.putIfAbsent(field, cell.col()));
        }
        return columns;
    }

    /** Whether a row repeats the header, as BOQs printed across pages do. */
    static boolean isHeaderLike(TableRow row) {
        return columnsOf(row).size() >= MIN_FIELDS;
    }

    static Optional<BoqField> fieldOf(String header) {
        String h = normalize(header);
        if (h.isEmpty() || h.length() > 60 || h.contains("words")) {
            return Optional.empty();
        }
        if (h.startsWith("qty") || h.startsWith("qnty") || h.startsWith("qnt") || h.contains("quantit")
                || h.contains("qty")) {
            return Optional.of(BoqField.QUANTITY);
        }
        if ((h.startsWith("rate") || h.contains("unitrate") || h.contains("unitprice") || h.equals("price")
                || h.startsWith("rateper")) && !h.contains("amount")) {
            return Optional.of(BoqField.RATE);
        }
        if (h.contains("description") || DESCRIPTION_WORDS.contains(h) || h.startsWith("particular")) {
            return Optional.of(BoqField.DESCRIPTION);
        }
        if (UNIT_WORDS.contains(h) || (h.startsWith("unit") && h.length() <= 12)) {
            return Optional.of(BoqField.UNIT);
        }
        if (CODE_WORDS.contains(h) || h.startsWith("slno") || h.startsWith("srno") || h.startsWith("itemno")
                || h.startsWith("itemcode")) {
            return Optional.of(BoqField.ITEM_CODE);
        }
        if (h.startsWith("amount") || AMOUNT_WORDS.contains(h)) {
            return Optional.of(BoqField.AMOUNT);
        }
        return Optional.empty();
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}

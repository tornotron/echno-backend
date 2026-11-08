package org.tornotron.echno_backend.modules.documentingestion.parsing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One line of {@code tables.jsonl}, as the worker contract (section 7) states it: one non-empty row
 * of a sheet, or one row of a table on a PDF page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TableRow(String sheet, Integer sheetIndex, Integer page, int row, boolean hidden, List<Cell> cells) {

    public TableRow {
        cells = cells == null ? List.of() : cells;
    }

    /** The cell in the 1-based column, or null. */
    public Cell cell(int col) {
        for (Cell c : cells) {
            if (c.col() == col) {
                return c;
            }
        }
        return null;
    }

    /**
     * One cell. {@code type} is TEXT, NUMBER, DATE, BOOL or EMPTY; {@code text} is what a person sees.
     * {@code mergeOrigin} names the top-left cell of a merged range for a covered cell.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cell(String ref, int col, JsonNode value, String type, String text, boolean formula,
                       boolean noCachedValue, String mergeOrigin) {

        /** A formula with no cached result is not empty: it holds a value the file does not state. */
        public boolean isEmpty() {
            if (noCachedValue) {
                return false;
            }
            return "EMPTY".equals(type) || ((value == null || value.isNull()) && (text == null || text.isBlank()));
        }

        public String shownText() {
            if (text != null && !text.isBlank()) {
                return text.strip();
            }
            if (value == null || value.isNull()) {
                return "";
            }
            return value.isTextual() ? value.asText().strip() : value.toString();
        }
    }
}

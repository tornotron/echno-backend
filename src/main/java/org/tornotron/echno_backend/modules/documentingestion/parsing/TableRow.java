package org.tornotron.echno_backend.modules.documentingestion.parsing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One line of {@code tables.jsonl}, as the worker contract (section 7) states it: one non-empty row
 * of a sheet, or one row of a table on a PDF page. {@code table} (worker 0.2.0, PDF rows only) is the
 * 1-based index of the table on its page, so two tables on one page do not share row numbers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TableRow(String sheet, Integer sheetIndex, Integer page, Integer table, int row, boolean hidden,
                       List<Cell> cells) {

    @JsonCreator
    public TableRow {
        cells = cells == null ? List.of() : cells;
    }

    /** A sheet row, which has no table index. */
    public TableRow(String sheet, Integer sheetIndex, Integer page, int row, boolean hidden, List<Cell> cells) {
        this(sheet, sheetIndex, page, null, row, hidden, cells);
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
     * {@code mergeOrigin} names the top-left cell of a merged range for a covered cell. {@code box}
     * (worker 0.2.0, PDF cells only) is the cell's normalized {@code [x0, y0, x1, y1]}, or null.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cell(String ref, int col, JsonNode value, String type, String text, boolean formula,
                       boolean noCachedValue, String mergeOrigin, List<Double> box) {

        @JsonCreator
        public Cell {
        }

        /** A sheet cell, which has no box. */
        public Cell(String ref, int col, JsonNode value, String type, String text, boolean formula,
                    boolean noCachedValue, String mergeOrigin) {
            this(ref, col, value, type, text, formula, noCachedValue, mergeOrigin, null);
        }

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

package org.tornotron.echno_backend.modules.documentingestion.extraction.boq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow.Cell;

/**
 * Builds {@code tables.jsonl} rows in the worker contract's shape, so extractor tests read exactly
 * what the worker writes. Text cells give their shown text; number cells give a value and the text a
 * person sees, such as {@code 8,75,000}.
 */
public final class BoqRows {

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final String sheet;
    private final int sheetIndex;
    private final List<TableRow> rows = new ArrayList<>();

    public BoqRows(String sheet, int sheetIndex) {
        this.sheet = sheet;
        this.sheetIndex = sheetIndex;
    }

    /** One row; each value is a String (TEXT), a {@link Num} (NUMBER), a {@link Cell}, or null (skipped). */
    public BoqRows row(int row, Object... values) {
        return row(row, false, values);
    }

    public BoqRows hiddenRow(int row, Object... values) {
        return row(row, true, values);
    }

    private BoqRows row(int row, boolean hidden, Object... values) {
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            int col = i + 1;
            String ref = column(col) + row;
            Object v = values[i];
            if (v == null) {
                continue;
            }
            if (v instanceof Cell c) {
                cells.add(new Cell(ref, col, c.value(), c.type(), c.text(), c.formula(), c.noCachedValue(), c.mergeOrigin()));
            } else if (v instanceof Num n) {
                cells.add(new Cell(ref, col, JSON.numberNode(new BigDecimal(n.value())), "NUMBER", n.text(), false, false, null));
            } else {
                cells.add(new Cell(ref, col, JSON.textNode(v.toString()), "TEXT", v.toString(), false, false, null));
            }
        }
        rows.add(new TableRow(sheet, sheetIndex, null, row, hidden, cells));
        return this;
    }

    public List<TableRow> rows() {
        return List.copyOf(rows);
    }

    /** The rows as {@code tables.jsonl} bytes. */
    public byte[] jsonl() {
        StringBuilder out = new StringBuilder();
        try {
            for (TableRow r : rows) {
                out.append(MAPPER.writeValueAsString(r)).append('\n');
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public record Num(String value, String text) {
    }

    public static Num num(String value) {
        return new Num(value, value);
    }

    public static Num num(String value, String text) {
        return new Num(value, text);
    }

    public static Cell noCache() {
        return new Cell(null, 0, null, "EMPTY", "", true, true, null);
    }

    public static Cell mergedFrom(String origin) {
        return new Cell(null, 0, null, "EMPTY", "", false, false, origin);
    }

    public static Cell formulaText(String text) {
        return new Cell(null, 0, JSON.textNode(text), "TEXT", text, true, false, null);
    }

    static String column(int col) {
        StringBuilder s = new StringBuilder();
        while (col > 0) {
            int rem = (col - 1) % 26;
            s.insert(0, (char) ('A' + rem));
            col = (col - 1) / 26;
        }
        return s.toString();
    }

    /** A worker golden output, copied from the worker repository's fixtures. */
    public static List<TableRow> golden(String name) {
        try (InputStream in = BoqRows.class.getResourceAsStream("/document-ingestion/" + name)) {
            return ParsedOutputReader.parseTables(in.readAllBytes(), MAPPER);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] resource(String name) {
        try (InputStream in = BoqRows.class.getResourceAsStream("/document-ingestion/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

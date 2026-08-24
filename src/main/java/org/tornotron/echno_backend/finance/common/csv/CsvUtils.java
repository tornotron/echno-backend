package org.tornotron.echno_backend.finance.common.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, dependency-free CSV reader and writer for the finance interchange endpoints.
 *
 * <p>Follows the common CSV conventions (the shape RFC 4180 describes): fields are separated by
 * commas and rows by newlines; a field is quoted with double quotes when it contains a comma, a
 * quote, or a line break; and a literal quote inside a quoted field is written as two quotes.
 * Reading accepts both LF and CRLF line endings and unquotes fields symmetrically, so a file this
 * class writes round-trips back through {@link #parse} unchanged.
 */
public final class CsvUtils {

    private CsvUtils() {}

    /**
     * Renders a single record as a CSV line (without a trailing newline), quoting fields where
     * needed. A null field is written as an empty field.
     */
    public static String toLine(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(fields.get(i)));
        }
        return sb.toString();
    }

    private static String escape(String field) {
        if (field == null) {
            return "";
        }
        boolean mustQuote = field.indexOf(',') >= 0
                || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0;
        if (!mustQuote) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    /**
     * Parses CSV text into a list of records, each a list of field values. Quoted fields may span
     * commas, quotes (doubled), and line breaks. A trailing newline does not yield an extra empty
     * record. Blank lines outside a quoted field are skipped.
     */
    public static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }

        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        int i = 0;
        int n = content.length();

        while (i < n) {
            char c = content.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }

            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    fieldStarted = true;
                    i++;
                }
                case ',' -> {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    fieldStarted = true;
                    i++;
                }
                case '\r' -> {
                    // Treat CR or CRLF as one row terminator.
                    endRow(rows, currentRow, field, fieldStarted);
                    currentRow = new ArrayList<>();
                    field.setLength(0);
                    fieldStarted = false;
                    i += (i + 1 < n && content.charAt(i + 1) == '\n') ? 2 : 1;
                }
                case '\n' -> {
                    endRow(rows, currentRow, field, fieldStarted);
                    currentRow = new ArrayList<>();
                    field.setLength(0);
                    fieldStarted = false;
                    i++;
                }
                default -> {
                    field.append(c);
                    fieldStarted = true;
                    i++;
                }
            }
        }

        // Flush the final field/row if the content did not end on a newline.
        if (fieldStarted || !currentRow.isEmpty() || field.length() > 0) {
            currentRow.add(field.toString());
            rows.add(currentRow);
        }
        return rows;
    }

    private static void endRow(List<List<String>> rows, List<String> currentRow,
                               StringBuilder field, boolean fieldStarted) {
        if (!fieldStarted && currentRow.isEmpty()) {
            // A wholly blank line: skip it.
            return;
        }
        currentRow.add(field.toString());
        rows.add(currentRow);
    }
}

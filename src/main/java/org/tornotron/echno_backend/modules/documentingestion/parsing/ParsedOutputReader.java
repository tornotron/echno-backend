package org.tornotron.echno_backend.modules.documentingestion.parsing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;

/**
 * Reads a parsed document's outputs back from the object store, under caps, so one large workbook
 * cannot decide how much heap a request takes. The prefix comes from the document row, which the
 * ingestor checked to lie inside the document's organization.
 */
@Component
public class ParsedOutputReader {

    static final long MAX_META_BYTES = 4L * 1024 * 1024;
    /** About 200,000 rows of a narrow sheet. A BOQ is a few thousand. */
    static final long MAX_TABLES_BYTES = 96L * 1024 * 1024;

    private final FileStorageService storage;
    private final ObjectMapper objectMapper;

    public ParsedOutputReader(FileStorageService storage, ObjectMapper objectMapper) {
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    public WorkbookMeta meta(String outputPrefix) {
        byte[] bytes = read(outputPrefix + DocumentIngestionStorageLayout.META_FILE, MAX_META_BYTES);
        try {
            return objectMapper.readValue(bytes, WorkbookMeta.class);
        } catch (IOException e) {
            throw new IllegalStateException("The worker's meta.json could not be read: " + e.getMessage(), e);
        }
    }

    public List<TableRow> tables(String outputPrefix) {
        byte[] bytes = read(outputPrefix + DocumentIngestionStorageLayout.TABLES_FILE, MAX_TABLES_BYTES);
        return parseTables(bytes, objectMapper);
    }

    /** Parses {@code tables.jsonl}; blank lines are skipped and a malformed line names its number. */
    public static List<TableRow> parseTables(byte[] bytes, ObjectMapper objectMapper) {
        List<TableRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    rows.add(objectMapper.readValue(line, TableRow.class));
                } catch (IOException e) {
                    throw new IllegalStateException("Line " + number + " of tables.jsonl could not be read", e);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("tables.jsonl could not be read", e);
        }
        return rows;
    }

    private byte[] read(String key, long maxBytes) {
        return storage.readObject(key, maxBytes)
                .orElseThrow(() -> new InvalidRequestException("The reading of this file is missing or larger than "
                        + (maxBytes / (1024 * 1024)) + " MB. Read the file again, or split the workbook."))
                .content();
    }
}

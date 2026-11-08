package org.tornotron.echno_backend.modules.documentingestion.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;

class ParsedOutputReaderTest {

    final FileStorageService storage = mock(FileStorageService.class);
    final ParsedOutputReader reader = new ParsedOutputReader(storage, new ObjectMapper());
    final String key = "doc-ingest/1/p/d/output/" + DocumentIngestionStorageLayout.TABLES_FILE;

    @Test
    void noTablesFileReadsAsNoneButOneThatCannotBeReadFails() {
        when(storage.objectExists(key)).thenReturn(false);
        assertThat(reader.tablesIfWritten("doc-ingest/1/p/d/output/")).isEmpty();

        when(storage.objectExists(key)).thenReturn(true);
        when(storage.readObject(eq(key), anyLong())).thenReturn(Optional.of(new FileStorageService.StoredObject(key,
                "application/json", "{not json".getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> reader.tablesIfWritten("doc-ingest/1/p/d/output/")).isInstanceOf(IllegalStateException.class);

        when(storage.readObject(eq(key), anyLong())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reader.tablesIfWritten("doc-ingest/1/p/d/output/")).isInstanceOf(RuntimeException.class);
    }
}

package org.tornotron.echno_backend.modules.documentingestion.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reader accepts worker 0.2.0's additive keys, exactly as the worker writes them (contract
 * section 7), still accepts outputs without them, and ignores keys it does not know.
 */
class WorkerOutputShapesTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void pagesCarryTheirScriptWhichDiffersFromAnAbsentKey() {
        String jsonl = String.join("\n",
                "{\"page\":1,\"method\":\"TEXT\",\"width\":595.28,\"height\":841.89,\"lines\":[{\"text\":\"NOTICE\","
                        + "\"box\":[0.1,0.05,0.4,0.07],\"ocrConfidence\":null}],\"status\":\"OK\",\"reason\":null,"
                        + "\"script\":\"LATIN\",\"later\":{\"x\":1}}",
                "{\"page\":2,\"method\":\"OCR\",\"width\":595.28,\"height\":841.89,\"lines\":[{\"text\":\"Scanned\","
                        + "\"box\":[0.1,0.05,0.4,0.07],\"ocrConfidence\":0.873}],\"status\":\"OK\",\"reason\":null,\"script\":null}",
                "",
                "{\"page\":3,\"method\":\"NONE\",\"width\":595.28,\"height\":841.89,\"lines\":[],\"status\":\"FAILED\","
                        + "\"reason\":\"The page is damaged.\"}");

        List<PageText> pages = ParsedOutputReader.parsePages(jsonl.getBytes(StandardCharsets.UTF_8), json);

        assertThat(pages).hasSize(3);
        assertThat(pages.get(0).script()).isEqualTo("LATIN");
        assertThat(pages.get(0).lines().get(0).box()).containsExactly(0.1, 0.05, 0.4, 0.07);
        assertThat(pages.get(1).isOcr()).isTrue();
        assertThat(pages.get(1).lines().get(0).ocrConfidence()).isEqualTo(0.873);
        assertThat(pages.get(1).scriptReported()).isTrue();
        assertThat(pages.get(1).script()).isNull();
        assertThat(pages.get(2).scriptReported()).as("an older worker wrote no script").isFalse();
        assertThat(pages.get(2).isFailed()).isTrue();
    }

    @Test
    void pdfTableRowsCarryTheirTableIndexAndCellBoxes() {
        String jsonl = "{\"sheet\":null,\"sheetIndex\":null,\"page\":12,\"table\":2,\"row\":3,\"hidden\":false,\"cells\":["
                + "{\"ref\":\"A3\",\"col\":1,\"value\":\"1.01\",\"type\":\"TEXT\",\"text\":\"1.01\",\"formula\":false,"
                + "\"noCachedValue\":false,\"mergeOrigin\":null,\"box\":[0.05,0.4,0.12,0.42]},"
                + "{\"ref\":\"B3\",\"col\":2,\"value\":null,\"type\":\"EMPTY\",\"text\":\"\",\"formula\":false,"
                + "\"noCachedValue\":false,\"mergeOrigin\":\"B2\",\"box\":null}]}\n"
                + "{\"sheet\":\"BOQ\",\"sheetIndex\":0,\"page\":null,\"row\":4,\"hidden\":false,\"cells\":[{\"ref\":\"A4\","
                + "\"col\":1,\"value\":2,\"type\":\"NUMBER\",\"text\":\"2\",\"formula\":false,\"noCachedValue\":false,"
                + "\"mergeOrigin\":null}]}";

        List<TableRow> rows = ParsedOutputReader.parseTables(jsonl.getBytes(StandardCharsets.UTF_8), json);

        assertThat(rows.get(0).table()).isEqualTo(2);
        assertThat(rows.get(0).page()).isEqualTo(12);
        assertThat(rows.get(0).cell(1).box()).containsExactly(0.05, 0.4, 0.12, 0.42);
        assertThat(rows.get(0).cell(2).mergeOrigin()).isEqualTo("B2");
        assertThat(rows.get(1).table()).isNull();
        assertThat(rows.get(1).cell(1).box()).isNull();
        assertThat(rows.get(1).cell(1).value().asInt()).isEqualTo(2);
    }
}

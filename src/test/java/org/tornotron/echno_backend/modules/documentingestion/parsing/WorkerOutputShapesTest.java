package org.tornotron.echno_backend.modules.documentingestion.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reader accepts worker 0.2.0's and 0.3.0's additive keys and files, exactly as the worker writes them (contract
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

    @Test
    void titleBlocksAreReadAsTheWorkerWritesThemInPageOrder() {
        String jsonl = String.join("\n",
                "{\"page\": 2, \"status\": \"SKIPPED\", \"reason\": \"Not shaped like a drawing sheet.\", "
                        + "\"method\": \"NONE\", \"region\": null, \"regionSource\": null, \"image\": null, "
                        + "\"words\": [], \"lines\": []}",
                "{\"page\": 1, \"status\": \"OK\", \"reason\": null, \"method\": \"TEXT\", "
                        + "\"region\": [0.7777, 0.8218, 0.9916, 0.9881], \"regionSource\": \"RULED_BOX\", "
                        + "\"image\": \"titleblock/1.webp\", \"words\": [{\"text\": \"RCB-AR-101\", "
                        + "\"box\": [0.8406, 0.8924, 0.8755, 0.9008], \"ocrConfidence\": null}], "
                        + "\"lines\": [{\"text\": \"DRAWING NO.\", \"box\": [0.7802, 0.8813, 0.8062, 0.8867], "
                        + "\"ocrConfidence\": null}], \"later\": 1}",
                "",
                "{\"page\": 3, \"status\": \"OK\", \"reason\": null, \"method\": \"OCR\", "
                        + "\"region\": [0.75, 0.75, 1.0, 1.0], \"regionSource\": \"DEFAULT\", \"image\": \"titleblock/3.webp\", "
                        + "\"words\": [{\"text\": \"REV\", \"box\": [0.8, 0.9, 0.82, 0.91], \"ocrConfidence\": 0.81}], "
                        + "\"lines\": []}");

        List<TitleBlock> blocks = ParsedOutputReader.parseTitleBlocks(jsonl.getBytes(StandardCharsets.UTF_8), json);

        assertThat(blocks).extracting(TitleBlock::page).containsExactly(1, 2, 3);
        assertThat(blocks.get(0).isOk()).isTrue();
        assertThat(blocks.get(0).region()).containsExactly(0.7777, 0.8218, 0.9916, 0.9881);
        assertThat(blocks.get(0).regionSource()).isEqualTo("RULED_BOX");
        assertThat(blocks.get(0).image()).isEqualTo("titleblock/1.webp");
        assertThat(blocks.get(0).words()).singleElement().satisfies(w -> {
            assertThat(w.text()).isEqualTo("RCB-AR-101");
            assertThat(w.box()).containsExactly(0.8406, 0.8924, 0.8755, 0.9008);
        });
        assertThat(blocks.get(0).lines()).extracting(PageText.Line::text).containsExactly("DRAWING NO.");
        assertThat(blocks.get(1).isSkipped()).isTrue();
        assertThat(blocks.get(1).region()).isNull();
        assertThat(blocks.get(2).isOcr()).isTrue();
        assertThat(blocks.get(2).words().get(0).ocrConfidence()).isEqualTo(0.81);
    }

    @Test
    void aMalformedTitleBlockLineNamesItsNumber() {
        byte[] bytes = "{\"page\": 1, \"status\": \"OK\"}\n{not json".getBytes(StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ParsedOutputReader.parseTitleBlocks(bytes, json))
                .hasMessageContaining("Line 2 of titleblock.jsonl");
    }

    @Test
    void theDxfReadingIgnoresKeysItDoesNotKnow() {
        String doc = "{\"dxfVersion\": \"AC1032\", \"insUnits\": null, \"properties\": [], \"layouts\": "
                + "[{\"name\": \"A1 Sheet\", \"modelSpace\": false, \"paperSize\": null, \"texts\": [], \"later\": 2}], "
                + "\"inserts\": [{\"layout\": \"A1 Sheet\", \"block\": \"TB\", \"handle\": \"1F\", "
                + "\"attributes\": [{\"tag\": \"DWG_NO\", \"value\": \"HRV-AR-101\"}]}], \"truncated\": false, "
                + "\"newKey\": {}}";

        DxfReading dxf = ParsedOutputReader.parseDxf(doc.getBytes(StandardCharsets.UTF_8), json);

        assertThat(dxf.insUnits()).isNull();
        assertThat(dxf.layouts()).singleElement().satisfies(l -> assertThat(l.modelSpace()).isFalse());
        assertThat(dxf.inserts()).singleElement().satisfies(i -> {
            assertThat(i.titleBlockLike()).as("an absent flag reads as false").isFalse();
            assertThat(i.path()).isEmpty();
            assertThat(i.attributes().get(0).value()).isEqualTo("HRV-AR-101");
        });
    }
}

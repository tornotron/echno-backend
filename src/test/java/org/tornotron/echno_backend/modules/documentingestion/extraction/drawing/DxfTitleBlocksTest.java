package org.tornotron.echno_backend.modules.documentingestion.extraction.drawing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.parsing.DxfReading;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;

/** The DXF title block: attributes by tag, one sheet per paper space layout, and the layout name as a number. */
class DxfTitleBlocksTest {

    static final String SAMPLE = """
            {"dxfVersion": "AC1032", "insUnits": 4,
             "properties": [{"name": "Client", "value": "Example Housing Board"}],
             "layouts": [{"name": "Model", "modelSpace": true, "paperSize": null},
                         {"name": "A1 Sheet", "modelSpace": false, "paperSize": "ISO_A1_(841.00_x_594.00_MM)",
                          "texts": [{"text": "NOT FOR CONSTRUCTION", "kind": "TEXT", "layer": "0", "position": [20.0, 20.0]}]},
                         {"name": "HRV-AR-102", "modelSpace": false, "paperSize": null}],
             "inserts": [{"layout": "A1 Sheet", "block": "TITLE_BLOCK_A1", "path": ["A1_BORDER"], "handle": "A3",
                          "titleBlockLike": true,
                          "attributes": [{"tag": "DWG_NO", "value": "HRV-AR-101", "constant": false, "invisible": false},
                                         {"tag": "TITLE_1", "value": "FIRST FLOOR", "constant": false, "invisible": false},
                                         {"tag": "TITLE_2", "value": "FURNITURE LAYOUT", "constant": false, "invisible": false},
                                         {"tag": "REV", "value": "C", "constant": false, "invisible": false},
                                         {"tag": "SCALE", "value": "1:100", "constant": false, "invisible": false},
                                         {"tag": "DATE", "value": "", "constant": false, "invisible": false}],
                          "texts": [{"text": "DRAWING NO.", "kind": "TEXT", "layer": "0", "position": [2.0, 50.0]}],
                          "later": true},
                         {"layout": "Model", "block": "TB_REF", "path": [], "handle": "B1", "titleBlockLike": true,
                          "attributes": [{"tag": "DWG_NO", "value": "OLD-001", "constant": false, "invisible": false}]},
                         {"layout": "HRV-AR-102", "block": "STAMP", "path": [], "handle": "C1", "titleBlockLike": true,
                          "attributes": [{"tag": "REVISION", "value": "A", "constant": true, "invisible": false}]}],
             "truncated": false}""";

    @Test
    void eachPaperSheetWithATitleBlockIsOneDrawing() {
        DxfReading dxf = ParsedOutputReader.parseDxf(SAMPLE.getBytes(StandardCharsets.UTF_8), new ObjectMapper());

        List<DxfTitleBlocks.Sheet> sheets = DxfTitleBlocks.read(dxf);

        assertThat(sheets).extracting(DxfTitleBlocks.Sheet::layout).as("model space is left out beside paper sheets")
                .containsExactly("A1 Sheet", "HRV-AR-102");
        DxfTitleBlocks.Sheet a1 = sheets.get(0);
        assertThat(a1.values().get(DrawingFields.NUMBER)).isEqualTo(new DxfTitleBlocks.Value("HRV-AR-101", "DWG_NO"));
        assertThat(a1.values().get(DrawingFields.TITLE).text()).isEqualTo("FIRST FLOOR FURNITURE LAYOUT");
        assertThat(a1.values().get(DrawingFields.REVISION).text()).isEqualTo("C");
        assertThat(a1.values().get(DrawingFields.SCALE).text()).isEqualTo("1:100");
        assertThat(a1.values()).as("an empty attribute is not a value").doesNotContainKey(DrawingFields.DATE);
        assertThat(a1.values().get(DrawingFields.DISCIPLINE).text()).isEqualTo("Architectural");
        assertThat(a1.numberFromLayout()).isFalse();
        DxfTitleBlocks.Sheet named = sheets.get(1);
        assertThat(named.values().get(DrawingFields.NUMBER).text()).isEqualTo("HRV-AR-102");
        assertThat(named.numberFromLayout()).isTrue();
        assertThat(named.values().get(DrawingFields.REVISION).text()).as("a constant ATTDEF counts").isEqualTo("A");
        assertThat(dxf.properties()).singleElement().satisfies(p -> assertThat(p.value()).isEqualTo("Example Housing Board"));
    }

    @Test
    void aDxfWithNoTitleBlockGivesNoSheet() {
        DxfReading dxf = ParsedOutputReader.parseDxf(("{\"layouts\": [{\"name\": \"Model\", \"modelSpace\": true}], "
                + "\"inserts\": [], \"truncated\": true}").getBytes(StandardCharsets.UTF_8), new ObjectMapper());

        assertThat(DxfTitleBlocks.read(dxf)).isEmpty();
        assertThat(dxf.truncated()).isTrue();
    }

    @Test
    void aFileNameGivesTheNumberAndATrailingRevision() {
        assertThat(DrawingFields.fromFileName("HRV-AR-301_RevB.dwg")).containsExactly("HRV-AR-301", "B");
        assertThat(DrawingFields.fromFileName("drawings/HRV-ST-110.dwg")).containsExactly("HRV-ST-110", null);
    }
}

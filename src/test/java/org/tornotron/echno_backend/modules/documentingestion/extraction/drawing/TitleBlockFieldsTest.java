package org.tornotron.echno_backend.modules.documentingestion.extraction.drawing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TitleBlock;

/** The title-block patterns: labels with values beside or below them, a revision table, and what fails. */
class TitleBlockFieldsTest {

    @Test
    void labelsWithValuesBelowAndBesideThemAreRead() {
        TitleBlock block = TitleBlocks.page(1)
                .row(0.84, 0.80, "TITLE")
                .row(0.85, 0.80, "GROUND", "FLOOR")
                .row(0.86, 0.80, "PLAN")
                .row(0.88, 0.80, "DRAWING", "NO.")
                .row(0.88, 0.93, "REV")
                .row(0.89, 0.80, "RCB-AR-101")
                .row(0.89, 0.93, "B")
                .row(0.92, 0.80, "SCALE:", "1:100", "DATE:", "12.03.2026")
                .build();

        Map<String, TitleBlockFields.Found> f = TitleBlockFields.read(block).fields();

        assertThat(value(f, DrawingFields.NUMBER)).isEqualTo("RCB-AR-101");
        assertThat(value(f, DrawingFields.REVISION)).isEqualTo("B");
        assertThat(value(f, DrawingFields.TITLE)).isEqualTo("GROUND FLOOR PLAN");
        assertThat(value(f, DrawingFields.SCALE)).isEqualTo("1:100");
        assertThat(value(f, DrawingFields.DATE)).isEqualTo("12.03.2026");
        assertThat(value(f, DrawingFields.DISCIPLINE)).as("from the AR code of the number").isEqualTo("Architectural");
        assertThat(f.get(DrawingFields.NUMBER).box()).as("the value's own box on the page")
                .containsExactly(0.80, 0.89, 0.80 + 0.006 * 10, 0.89 + TitleBlocks.H);
        assertThat(f).doesNotContainKey(DrawingFields.HISTORY);
    }

    @Test
    void aPrintedRevisionTableGivesTheHistoryOldestFirstAndTheCurrentRevision() {
        TitleBlock block = TitleBlocks.page(3)
                .row(0.70, 0.80, "REV").row(0.70, 0.83, "DATE").row(0.70, 0.87, "DESCRIPTION").row(0.70, 0.95, "BY")
                .row(0.71, 0.80, "B").row(0.71, 0.83, "12.03.2026").row(0.71, 0.87, "REVISED", "GRID")
                .row(0.71, 0.95, "KL")
                .row(0.72, 0.80, "A").row(0.72, 0.83, "01.02.2026").row(0.72, 0.87, "FIRST", "ISSUE")
                .row(0.72, 0.95, "JS")
                .row(0.80, 0.80, "DRG", "NO:", "HRV-ST-201")
                .build();

        Map<String, TitleBlockFields.Found> f = TitleBlockFields.read(block).fields();

        assertThat(value(f, DrawingFields.NUMBER)).isEqualTo("HRV-ST-201");
        assertThat(value(f, DrawingFields.REVISION)).as("the newest row of the table").isEqualTo("B");
        assertThat(value(f, DrawingFields.DATE)).isEqualTo("12.03.2026");
        assertThat(f.get(DrawingFields.REVISION).quote()).isEqualTo("B 12.03.2026 REVISED GRID KL");
        assertThat(value(f, DrawingFields.DISCIPLINE)).isEqualTo("Structural");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) f.get(DrawingFields.HISTORY).value();
        assertThat(history).extracting(r -> r.get("revision")).containsExactly("A", "B");
        assertThat(history.get(0)).containsEntry("date", "01.02.2026").containsEntry("description", "FIRST ISSUE");
        assertThat(history.get(1)).containsEntry("description", "REVISED GRID");
    }

    @Test
    void aGluedLabelAndAValueOnTheSameRowAreSplit() {
        TitleBlock block = TitleBlocks.page(2)
                .row(0.90, 0.80, "DWG.NO.", "NFC/EL/310", "SCALE:1:50", "REV", "P2")
                .build();

        Map<String, TitleBlockFields.Found> f = TitleBlockFields.read(block).fields();

        assertThat(value(f, DrawingFields.NUMBER)).isEqualTo("NFC/EL/310");
        assertThat(value(f, DrawingFields.SCALE)).isEqualTo("1:50");
        assertThat(value(f, DrawingFields.REVISION)).isEqualTo("P2");
        assertThat(value(f, DrawingFields.DISCIPLINE)).isEqualTo("Electrical");
    }

    @Test
    void aBlockWithNoNumberLabelIsNotReadByPatterns() {
        TitleBlock block = TitleBlocks.page(4)
                .row(0.85, 0.80, "NORTHFIELD", "CLINIC")
                .row(0.86, 0.80, "NFC-AR-204", "R2")
                .build();

        assertThat(TitleBlockFields.read(block).hasNumber()).isFalse();
    }

    @Test
    void linesWithNoWordsAreCutIntoWords() {
        TitleBlock fromWords = TitleBlocks.page(5).row(0.90, 0.80, "DRAWING", "NO.", "WTP-CV-012").build();
        TitleBlock linesOnly = new TitleBlock(5, "OK", null, "TEXT", fromWords.region(), "DEFAULT", null, List.of(),
                fromWords.lines());

        assertThat(value(TitleBlockFields.read(linesOnly).fields(), DrawingFields.NUMBER)).isEqualTo("WTP-CV-012");
    }

    @Test
    void anOcrMisreadOfNoAsZeroStillNamesTheLabel() {
        TitleBlock block = TitleBlocks.ocrPage(6).row(0.90, 0.80, "Drawing", "N0.", "RCB-ST-140").build();

        assertThat(value(TitleBlockFields.read(block).fields(), DrawingFields.NUMBER)).isEqualTo("RCB-ST-140");
    }

    @Test
    void anEmptyBlockReadsNothing() {
        TitleBlock block = new TitleBlock(7, "OK", "Blank", "NONE", null, "DEFAULT", null, List.of(),
                List.of(new PageText.Line("", null, null)));

        assertThat(TitleBlockFields.read(block).fields()).isEmpty();
    }

    private static Object value(Map<String, TitleBlockFields.Found> fields, String field) {
        TitleBlockFields.Found f = fields.get(field);
        return f == null ? null : f.value();
    }
}

package org.tornotron.echno_backend.modules.documentingestion.extraction.milestone;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;

class MilestoneTablesTest {

    static TableRow sheetRow(int row, String... cells) {
        List<TableRow.Cell> out = new ArrayList<>();
        for (int c = 0; c < cells.length; c++) {
            if (cells[c] != null) {
                out.add(new TableRow.Cell((char) ('A' + c) + Integer.toString(row), c + 1, TextNode.valueOf(cells[c]),
                        "TEXT", cells[c], false, false, null));
            }
        }
        return new TableRow("Schedule", 0, null, row, false, out);
    }

    static TableRow pdfRow(int page, int row, String... cells) {
        List<TableRow.Cell> out = new ArrayList<>();
        for (int c = 0; c < cells.length; c++) {
            out.add(new TableRow.Cell(null, c + 1, TextNode.valueOf(cells[c]), "TEXT", cells[c], false, false, null,
                    List.of(0.1 * c, 0.1 * row, 0.1 * c + 0.1, 0.1 * row + 0.05)));
        }
        return new TableRow(null, null, page, 1, row, false, out);
    }

    static ExtractContext context(DocumentFileType type, List<TableRow> rows) {
        return new ExtractContext(UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(), "f", type, "a".repeat(64),
                UUID.randomUUID(), "MILESTONE_SCHEDULE", null, null, List.of(), null, Map.of(), rows);
    }

    static List<String> codes(ProposedCandidate c) {
        return c.flags().stream().map(CandidateFlag::code).toList();
    }

    @Test
    void aHeaderedSheetGivesOneMilestonePerNamedRowWithTheCellOfEachValue() {
        List<ProposedCandidate> found = MilestoneTables.read(context(DocumentFileType.XLSX, List.of(
                sheetRow(1, "Payment schedule"),
                sheetRow(3, "Sl. No.", "Description of milestone", "Target date", "Payment (%)"),
                sheetRow(4, "1", "Foundations", "15.03.2027", "40"),
                sheetRow(5, "2", "Roof slab", "15.08.2027", "60"),
                sheetRow(6, null, "Total", null, "100"))), false);

        assertThat(found).extracting(ProposedCandidate::naturalKey).containsExactly("Foundations", "Roof slab");
        ProposedCandidate first = found.get(0);
        assertThat(first.payload()).containsEntry("time", "15.03.2027").containsEntry("percent", "40").containsEntry("order", 0);
        assertThat(first.sources()).extracting(s -> s.field() + "@" + s.cell()).containsExactly("name@B4", "time@C4",
                "percent@D4");
        assertThat(first.sources()).allSatisfy(s -> assertThat(s.quoteVerified()).isTrue());
        assertThat(found).allSatisfy(c -> assertThat(codes(c)).isEmpty());
    }

    @Test
    void percentagesNotTotalling100AreFlaggedAndAnEarlierDateIsFlaggedOnItsRow() {
        List<ProposedCandidate> found = MilestoneTables.read(context(DocumentFileType.XLSX, List.of(
                sheetRow(1, "Milestone", "Date", "%"),
                sheetRow(2, "Foundations", "15.08.2027", "40"),
                sheetRow(3, "Roof slab", "15.03.2027", "55"))), false);

        assertThat(found).allSatisfy(c -> assertThat(codes(c)).contains(MilestoneTables.PERCENT_TOTAL));
        assertThat(codes(found.get(0))).doesNotContain(MilestoneTables.DATE_ORDER);
        assertThat(codes(found.get(1))).contains(MilestoneTables.DATE_ORDER);
        assertThat(found.get(0).flags().get(0).message()).contains("95");
    }

    @Test
    void aPercentInAnAmountColumnIsAPercentAndARelativeTimeIsFlagged() {
        List<ProposedCandidate> found = MilestoneTables.read(context(DocumentFileType.PDF, List.of(
                pdfRow(17, 1, "Milestone", "Time allowed", "Amount to be withheld"),
                pdfRow(17, 2, "Completion of pile foundation", "30 days", "1.25 % Tendered value"))), false);

        assertThat(found).singleElement().satisfies(c -> {
            assertThat(c.payload()).containsEntry("percent", "1.25 % Tendered value").doesNotContainKey("amount");
            assertThat(codes(c)).contains(MilestoneTables.RELATIVE_TIME, MilestoneTables.PERCENT_TOTAL);
            assertThat(c.sources()).allSatisfy(s -> {
                assertThat(s.page()).isEqualTo(17);
                assertThat(s.box()).hasSize(4);
                assertThat(s.sheet()).isNull();
            });
        });
    }

    @Test
    void aTableRunningOnToTheNextPageKeepsItsColumns() {
        List<ProposedCandidate> found = MilestoneTables.read(context(DocumentFileType.PDF, List.of(
                pdfRow(3, 1, "Milestone", "Month", "Payment %"),
                pdfRow(3, 2, "Foundations", "Month 3", "50"),
                pdfRow(4, 1, "Handover", "Month 12", "50"))), false);

        assertThat(found).extracting(ProposedCandidate::naturalKey).containsExactly("Foundations", "Handover");
        assertThat(found).allSatisfy(c -> assertThat(codes(c)).doesNotContain(MilestoneTables.PERCENT_TOTAL));
    }

    @Test
    void contractDataNeedsAHeaderThatSaysMilestone() {
        List<TableRow> rows = List.of(sheetRow(1, "Description", "Date", "Amount"), sheetRow(2, "Advance", "01.01.2027", "5000"));
        assertThat(MilestoneTables.read(context(DocumentFileType.XLSX, rows), true)).isEmpty();
        assertThat(MilestoneTables.read(context(DocumentFileType.XLSX, rows), false)).hasSize(1);
    }
}

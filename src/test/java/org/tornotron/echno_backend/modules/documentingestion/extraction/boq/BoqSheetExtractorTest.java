package org.tornotron.echno_backend.modules.documentingestion.extraction.boq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqRows.formulaText;
import static org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqRows.mergedFrom;
import static org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqRows.noCache;
import static org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqRows.num;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqWarning;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqSheetExtractor.Line;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqSheetExtractor.Mapping;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqSheetExtractor.Result;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;

/**
 * The BOQ reading of section 4.2 of the design, on rows in the worker contract's shape: the ticket's
 * sample, the worker's golden workbook, 2,000 rows, three sheets, and every check as a flag.
 */
class BoqSheetExtractorTest {

    static final Object[] HEADER = {"Item", "Description", "Unit", "Qty", "Rate", "Amount"};

    static Mapping standard(int headerRow) {
        Map<BoqField, Integer> columns = new EnumMap<>(BoqField.class);
        columns.put(BoqField.ITEM_CODE, 1);
        columns.put(BoqField.DESCRIPTION, 2);
        columns.put(BoqField.UNIT, 3);
        columns.put(BoqField.QUANTITY, 4);
        columns.put(BoqField.RATE, 5);
        columns.put(BoqField.AMOUNT, 6);
        return new Mapping(headerRow, columns, null);
    }

    static BoqRows ticketSample() {
        return new BoqRows("BOQ", 0)
                .row(1, HEADER)
                .row(2, num("1.01"), "Excavation in ordinary soil", "m³", num("2500", "2,500"), num("350", "₹350"),
                        num("875000", "₹8,75,000"))
                .row(3, num("1.02"), "PCC 1:4:8", "m³", num("320"), num("6500", "₹6,500"), num("2080000", "₹20,80,000"))
                .row(4, num("1.03"), "RCC M25", "m³", num("850"), num("9800", "₹9,800"), num("8330000", "₹83,30,000"))
                .row(5, num("1.04"), "Reinforcement steel Fe500", "MT", num("105"), num("68000", "₹68,000"),
                        num("7140000", "₹71,40,000"));
    }

    @Test
    void theTicketSampleLoadsFourCleanRowsWithExactNumbers() {
        List<TableRow> rows = ticketSample().rows();

        BoqHeaderDetector.Detection header = BoqHeaderDetector.detect(rows).orElseThrow();
        assertThat(header.headerRow()).isEqualTo(1);
        assertThat(header.complete()).isTrue();
        assertThat(header.columns()).isEqualTo(standard(1).columns());

        Result result = BoqSheetExtractor.extract(rows, false, standard(1));

        assertThat(result.lines()).extracting(Line::itemCode).containsExactly("1.01", "1.02", "1.03", "1.04");
        assertThat(result.lines()).allSatisfy(l -> assertThat(l.flags()).isEmpty());
        Line rcc = result.lines().get(2);
        assertThat(rcc.description()).isEqualTo("RCC M25");
        assertThat(rcc.unit()).isEqualTo("m³");
        assertThat(rcc.quantity()).isEqualByComparingTo("850");
        assertThat(rcc.rate()).isEqualByComparingTo("9800");
        assertThat(rcc.amount()).isEqualByComparingTo("8330000");
        assertThat(rcc.sources().get(BoqField.QUANTITY).ref()).isEqualTo("D4");
        assertThat(rcc.sources().get(BoqField.AMOUNT).text()).isEqualTo("₹83,30,000");
        assertThat(result.summary().items()).isEqualTo(4);
        assertThat(result.summary().rowsRead()).isEqualTo(4);
        assertThat(result.summary().warnings()).isEmpty();
    }

    @Test
    void theTicketSampleTypedAsTextReadsTheSameNumbersAndSaysSo() {
        List<TableRow> rows = new BoqRows("BOQ", 0)
                .row(1, HEADER)
                .row(2, "1.01", "Excavation in ordinary soil", "m³", "2,500", "₹350", "₹8,75,000")
                .row(3, "1.02", "PCC 1:4:8", "m³", "320", "Rs. 6,500", "20,80,000.00")
                .rows();

        Result result = BoqSheetExtractor.extract(rows, false, standard(1));

        assertThat(result.lines()).hasSize(2);
        Line first = result.lines().get(0);
        assertThat(first.quantity()).isEqualByComparingTo("2500");
        assertThat(first.rate()).isEqualByComparingTo("350");
        assertThat(first.amount()).isEqualByComparingTo("875000");
        assertThat(codes(first)).containsExactly("NUMBER_AS_TEXT");
        assertThat(result.lines().get(1).amount()).isEqualByComparingTo("2080000");
        assertThat(codes(result.lines().get(1))).containsExactly("NUMBER_AS_TEXT");
    }

    @Test
    void theWorkerGoldenWorkbookLosesNoRowAndFlagsWhatItHolds() {
        List<TableRow> all = BoqRows.golden("golden-xlsx.tables.jsonl");
        List<TableRow> boq = all.stream().filter(r -> r.sheetIndex() == 0).toList();

        BoqHeaderDetector.Detection header = BoqHeaderDetector.detect(boq).orElseThrow();
        assertThat(header.headerRow()).isEqualTo(2);
        assertThat(header.columns()).isEqualTo(standard(2).columns());
        assertThat(BoqHeaderDetector.detect(all.stream().filter(r -> r.sheetIndex() == 1).toList())).isEmpty();

        Result result = BoqSheetExtractor.extract(boq, false, standard(2));

        assertThat(result.lines()).extracting(Line::row).containsExactly(3, 4, 5, 6, 7, 8);
        assertThat(result.summary().rowsRead()).isEqualTo(6);
        Map<Integer, Line> byRow = result.lines().stream().collect(Collectors.toMap(Line::row, l -> l));
        assertThat(codes(byRow.get(3))).as("the amount is a formula with a cached result that agrees").isEmpty();
        assertThat(codes(byRow.get(4))).contains("NUMBER_AS_TEXT", "FORMULA_NOT_CACHED");
        assertThat(byRow.get(4).rate()).isEqualByComparingTo("123456.00");
        assertThat(byRow.get(4).amount()).isNull();
        assertThat(codes(byRow.get(5))).contains("HIDDEN_ROW", "UNIT_MISSING", "UNPRICED");
        assertThat(codes(byRow.get(6))).contains("QUANTITY_MISSING");
        assertThat(byRow.get(7).description()).isEqualTo("Merged description");
        assertThat(byRow.get(7).sources().get(BoqField.DESCRIPTION).ref()).isEqualTo("B6");
        assertThat(codes(byRow.get(7))).contains("MERGED_CELL");
        assertThat(codes(byRow.get(8))).contains("NOT_A_NUMBER");
    }

    @Test
    void theXlsGoldenReadsTheSameItemsAsTheXlsx() {
        List<TableRow> xls = BoqRows.golden("golden-xls.tables.jsonl").stream().filter(r -> r.sheetIndex() == 0).toList();

        Result result = BoqSheetExtractor.extract(xls, false, standard(2));

        // XLS exposes no formulas, so row 8 keeps only its date and boolean and reads as a heading.
        assertThat(result.lines()).extracting(Line::row).containsExactly(3, 4, 5, 6, 7);
        assertThat(result.summary().sections()).isEqualTo(1);
        assertThat(result.lines().get(0).amount()).isEqualByComparingTo("54225");
    }

    @Test
    void twoThousandRowsInTwentySectionsLoadWithNoRowLost() {
        BoqRows sheet = new BoqRows("BOQ", 0).row(1, "Bill of Quantities").row(2, HEADER).row(3, num("1"), num("2"),
                num("3"), num("4"), num("5"), num("6"));
        int row = 4;
        for (int section = 1; section <= 20; section++) {
            sheet.row(row++, "S" + section, "Section " + section);
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 1; i <= 100; i++) {
                BigDecimal qty = new BigDecimal(i).add(new BigDecimal("0.125"));
                BigDecimal rate = new BigDecimal(section * 10 + i);
                BigDecimal amount = qty.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
                total = total.add(amount);
                sheet.row(row++, section + "." + i, "Item " + section + "." + i, "cum", num(qty.toPlainString()),
                        num(rate.toPlainString()), num(amount.toPlainString()));
            }
            sheet.row(row++, null, "Sub total", null, null, null, num(total.toPlainString()));
        }

        Result result = BoqSheetExtractor.extract(sheet.rows(), false, standard(2));

        assertThat(result.lines()).hasSize(2000);
        assertThat(result.lines()).allSatisfy(l -> assertThat(l.flags()).isEmpty());
        assertThat(result.lines().stream().map(Line::itemCode).distinct().count()).isEqualTo(2000);
        assertThat(result.lines().get(150).section()).isEqualTo("S2 Section 2");
        assertThat(result.summary().sections()).isEqualTo(20);
        assertThat(result.summary().totals()).isEqualTo(20);
        assertThat(result.summary().ignoredRows()).as("the column-number row under the header").containsExactly(3);
        assertThat(result.summary().rowsRead()).isEqualTo(2000 + 20 + 20 + 1);
        assertThat(result.summary().warnings()).isEmpty();
    }

    @Test
    void threeSheetsWithTheirOwnLayoutsEachLoadWhole() {
        List<TableRow> civil = new BoqRows("Civil", 0)
                .row(1, HEADER)
                .row(2, "1", "Earthwork", "cum", num("100"), num("200"), num("20000"))
                .row(3, "2", "PCC", "cum", num("10"), num("5000"), num("50000"))
                .rows();
        // Title rows above the header, columns in another order, and words for the rate kept out.
        List<TableRow> electrical = new BoqRows("Electrical", 1)
                .row(1, "Name of work: Sample building")
                .row(2, "Schedule B")
                .row(3, "Sl. No.", "Description of Item", "Quantity", "Unit", "Rate (Rs.)", "Rate in words", "Amount (Rs.)")
                .row(4, "1", "Wiring point", num("40"), "point", num("650"), "Six hundred fifty", num("26000"))
                .row(5, "2", "Light fitting", num("12"), "nos", num("2400"), "Two thousand four hundred", num("28800"))
                .row(6, "3", "Earthing", num("2"), "set", num("9000"), "Nine thousand", num("18000"))
                .rows();
        List<TableRow> plumbing = new BoqRows("Plumbing", 2)
                .row(1, "Code", "Particulars", "UOM", "Qty", "Unit Rate", "Total")
                .row(2, "P1", "CPVC pipe 25 mm", "m", num("300"), num("180"), num("54000"))
                .row(3, "P1", "CPVC pipe 32 mm", "m", num("100"), num("240"), num("24000"))
                .rows();

        BoqHeaderDetector.Detection e = BoqHeaderDetector.detect(electrical).orElseThrow();
        assertThat(e.headerRow()).isEqualTo(3);
        assertThat(e.columns()).containsEntry(BoqField.QUANTITY, 3).containsEntry(BoqField.UNIT, 4)
                .containsEntry(BoqField.RATE, 5).containsEntry(BoqField.AMOUNT, 7);
        BoqHeaderDetector.Detection p = BoqHeaderDetector.detect(plumbing).orElseThrow();
        assertThat(p.columns()).isEqualTo(standard(1).columns());

        Result c = BoqSheetExtractor.extract(civil, false, standard(1));
        Result el = BoqSheetExtractor.extract(electrical, false,
                new Mapping(e.headerRow(), e.columns(), "E-"));
        Result pl = BoqSheetExtractor.extract(plumbing, false, new Mapping(1, p.columns(), "P-"));

        assertThat(c.lines()).hasSize(2);
        assertThat(el.lines()).extracting(Line::itemCode).containsExactly("E-1", "E-2", "E-3");
        assertThat(el.lines()).allSatisfy(l -> assertThat(l.flags()).isEmpty());
        assertThat(pl.lines()).hasSize(2);
        assertThat(codes(pl.lines().get(1))).containsExactly("DUPLICATE_CODE");
        assertThat(pl.lines().get(1).flags().get(0).message()).contains("P-P1", "row 2");
    }

    @Test
    void everyCheckRaisesItsFlag() {
        List<TableRow> rows = new BoqRows("BOQ", 0)
                .row(1, HEADER)
                .row(2, "A", "EARTHWORK")
                .row(3, "1", "Wrong amount", "cum", num("10"), num("100"), num("1500"))
                .row(4, "2", "Rate is text", "cum", num("10"), "1,23,456.00", null)
                .row(5, "3", "Amount formula never calculated", "cum", num("2"), num("50"), noCache())
                .row(6, "4", "Rate shows an error", "cum", num("2"), formulaText("#DIV/0!"), null)
                .hiddenRow(7, "5", "A hidden row", "cum", num("1"), num("1"), num("1"))
                .row(8, "6", "Site office", null, "LS", null, num("250000"))
                .row(9, "7", "Insurance", "Lump sum", null, null, num("40000"))
                .row(10, "8", "Provisional sum for testing", null, null, null, num("75000"))
                .row(11, "9", "Unpriced line", "sqm", num("40"), null, null)
                .row(12, "10", "Rate zero", "sqm", num("40"), num("0"), null)
                .row(13, "11", "No quantity", "sqm", null, num("10"), null)
                .row(14, null, "No code here", "nos", num("3"), num("10"), num("30"))
                .row(15, "12", "No unit", null, num("3"), num("10"), num("30"))
                .row(16, "12", "Code repeated", "nos", num("3"), num("10"), num("30"))
                .row(17, "13", "Fine quantity", "kg", num("1.23456"), num("10.555"), null)
                .row(18, "14", "Words in quantity", "kg", "as required", num("10"), null)
                .row(19, "X".repeat(60), "Long code", "u".repeat(40), num("1"), num("1"), num("1"))
                .row(20, "15", "Merged quantity below", "nos", num("3"), num("10"), num("30"))
                .row(21, "16", mergedFrom("B20"), "nos", num("3"), num("10"), num("30"))
                .row(22, null, "Total", null, null, null, num("999"))
                .rows();

        Result result = BoqSheetExtractor.extract(rows, false, standard(1));
        Map<String, Line> byDescription = result.lines().stream().collect(Collectors.toMap(Line::description, l -> l,
                (a, b) -> a));

        assertThat(codes(byDescription.get("Wrong amount"))).containsExactly("AMOUNT_MISMATCH");
        assertThat(byDescription.get("Wrong amount").section()).isEqualTo("A EARTHWORK");
        assertThat(codes(byDescription.get("Rate is text"))).containsExactly("NUMBER_AS_TEXT");
        assertThat(codes(byDescription.get("Amount formula never calculated"))).containsExactly("FORMULA_NOT_CACHED");
        assertThat(codes(byDescription.get("Rate shows an error"))).containsExactly("CELL_ERROR");
        assertThat(codes(byDescription.get("A hidden row"))).containsExactly("HIDDEN_ROW");

        Line siteOffice = byDescription.get("Site office");
        assertThat(codes(siteOffice)).containsExactly("LUMP_SUM");
        assertThat(siteOffice.quantity()).isEqualByComparingTo("1");
        assertThat(siteOffice.unit()).isEqualTo("LS");
        assertThat(siteOffice.rate()).as("a 1 LS line is priced at its amount").isEqualByComparingTo("250000");
        assertThat(siteOffice.sources().get(BoqField.RATE).ref()).isEqualTo("F8");
        assertThat(codes(byDescription.get("Insurance"))).containsExactly("LUMP_SUM");
        Line provisional = byDescription.get("Provisional sum for testing");
        assertThat(codes(provisional)).containsExactly("PROVISIONAL_SUM");
        assertThat(provisional.quantity()).isEqualByComparingTo("1");

        assertThat(codes(byDescription.get("Unpriced line"))).containsExactly("UNPRICED");
        assertThat(byDescription.get("Unpriced line").rate()).isEqualByComparingTo("0");
        assertThat(codes(byDescription.get("Rate zero"))).containsExactly("UNPRICED");
        assertThat(codes(byDescription.get("No quantity"))).containsExactly("QUANTITY_MISSING");
        assertThat(codes(byDescription.get("No code here"))).containsExactly("CODE_MISSING");
        assertThat(byDescription.get("No code here").itemCode()).isEqualTo("R14");
        assertThat(codes(byDescription.get("No unit"))).containsExactly("UNIT_MISSING");
        assertThat(codes(byDescription.get("Code repeated"))).containsExactly("DUPLICATE_CODE");

        Line fine = byDescription.get("Fine quantity");
        assertThat(codes(fine)).contains("PRECISION");
        assertThat(fine.quantity()).isEqualByComparingTo("1.235");
        assertThat(fine.rate()).isEqualByComparingTo("10.56");
        assertThat(codes(byDescription.get("Words in quantity"))).containsExactly("NOT_A_NUMBER");
        assertThat(codes(byDescription.get("Long code"))).containsExactly("TOO_LONG");
        assertThat(byDescription.get("Long code").flags().get(0).message()).contains("item code", "unit");
        Line merged = result.lines().stream().filter(l -> l.row() == 21).findFirst().orElseThrow();
        assertThat(merged.description()).isEqualTo("Merged quantity below");
        assertThat(codes(merged)).containsExactly("MERGED_CELL");

        assertThat(result.summary().warnings()).extracting(BoqWarning::code).containsExactly("SECTION_TOTAL_MISMATCH");
        assertThat(result.summary().warnings().get(0).row()).isEqualTo(22);
        assertThat(result.summary().items()).isEqualTo(19);
        assertThat(result.summary().flaggedItems()).as("all but the row above the merge").isEqualTo(18);
    }

    @Test
    void aHiddenSheetFlagsEveryRowAndARepeatedHeaderIsListedNotRead() {
        List<TableRow> rows = new BoqRows("Old", 3)
                .row(1, HEADER)
                .row(2, "1", "Kept item", "cum", num("1"), num("1"), num("1"))
                .row(3, HEADER)
                .row(4, "2", "After a page break", "cum", num("1"), num("1"), num("1"))
                .row(5, null, null, null, null, null, num("12345"))
                .rows();

        Result result = BoqSheetExtractor.extract(rows, true, standard(1));

        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines()).allSatisfy(l -> assertThat(codes(l)).containsExactly("HIDDEN_SHEET"));
        assertThat(result.summary().ignoredRows()).containsExactly(3, 5);
    }

    @Test
    void anUnmappableSheetSaysNoItems() {
        List<TableRow> rows = new BoqRows("Notes", 1).row(1, "Some note").rows();

        assertThat(BoqHeaderDetector.detect(rows)).isEmpty();
        Result result = BoqSheetExtractor.extract(rows, false, standard(0));
        assertThat(result.lines()).isEmpty();
        assertThat(result.summary().warnings()).extracting(BoqWarning::code).containsExactly("NO_ITEMS");
    }

    @Test
    void textNumbersReadInEitherGrouping() {
        assertThat(BoqNumbers.parseText("1,23,456.00")).isEqualByComparingTo("123456");
        assertThat(BoqNumbers.parseText("123,456.50")).isEqualByComparingTo("123456.5");
        assertThat(BoqNumbers.parseText("₹ 8,75,000/-")).isEqualByComparingTo("875000");
        assertThat(BoqNumbers.parseText("Rs.350")).isEqualByComparingTo("350");
        assertThat(BoqNumbers.parseText("INR 1,000")).isEqualByComparingTo("1000");
        assertThat(BoqNumbers.parseText("(1,500)")).isEqualByComparingTo("-1500");
        assertThat(BoqNumbers.parseText("12,34")).isNull();
        assertThat(BoqNumbers.parseText("as required")).isNull();
        assertThat(BoqNumbers.parseText("1.2.3")).isNull();
    }

    @Test
    void headerWordsMapToFields() {
        assertThat(BoqHeaderDetector.fieldOf("S. No.")).contains(BoqField.ITEM_CODE);
        assertThat(BoqHeaderDetector.fieldOf("Item No")).contains(BoqField.ITEM_CODE);
        assertThat(BoqHeaderDetector.fieldOf("Description of work")).contains(BoqField.DESCRIPTION);
        assertThat(BoqHeaderDetector.fieldOf("Item Description")).contains(BoqField.DESCRIPTION);
        assertThat(BoqHeaderDetector.fieldOf("Total Qty")).contains(BoqField.QUANTITY);
        assertThat(BoqHeaderDetector.fieldOf("Unit Rate")).contains(BoqField.RATE);
        assertThat(BoqHeaderDetector.fieldOf("Rate in words")).isEmpty();
        assertThat(BoqHeaderDetector.fieldOf("Amount in words")).isEmpty();
        assertThat(BoqHeaderDetector.fieldOf("Amount (₹)")).contains(BoqField.AMOUNT);
        assertThat(BoqHeaderDetector.fieldOf("Nos")).isEmpty();
    }

    private static Set<String> codes(Line line) {
        return line.flags().stream().map(CandidateFlag::code).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}

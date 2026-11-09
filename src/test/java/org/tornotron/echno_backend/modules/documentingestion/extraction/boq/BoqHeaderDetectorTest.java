package org.tornotron.echno_backend.modules.documentingestion.extraction.boq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqRows.num;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqSheetExtractor.Line;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqSheetExtractor.Mapping;
import org.tornotron.echno_backend.modules.documentingestion.extraction.boq.BoqSheetExtractor.Result;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;

/**
 * Header detection on the layout of the standard central e-procurement item-rate BOQ template, built
 * here with made-up work and figures: the rate and amount columns name more than their field, a row
 * of column numbers sits under the header, and the template's later columns do not count on cleanly.
 */
class BoqHeaderDetectorTest {

    static final Object[] TEMPLATE_HEADER = {"Sl. No.", "Item Description", "Item Code / Make", "Quantity", "Units",
            "Estimated Rate in Rs. P", "GST rate (%)", "BASIC RATE In Figures To be entered by the Bidder in Rs. P",
            "TOTAL AMOUNT With Taxes in Rs. P", "TOTAL AMOUNT Without Taxes", "TOTAL AMOUNT In Words"};

    static BoqRows templateSheet() {
        return new BoqRows("BoQ1", 0)
                .row(1, "Tender Inviting Authority: Fictional Works Division")
                .row(2, "Name of Work: Construction of a community hall at Riverside")
                .row(3, "Contract No: FWD/2026/17")
                .row(4, "Name of the Bidder/ Bidding Firm / Company :")
                .hiddenRow(5, "NUMBER #", "TEXT #", "TEXT #", "NUMBER #", "TEXT #", "NUMBER #", "NUMBER #", "NUMBER #",
                        "NUMBER #", "NUMBER #", "TEXT #")
                .row(6, TEMPLATE_HEADER)
                .hiddenRow(7, num("1"), num("2"), num("3"), num("4"), num("5"), num("6"), num("7"), num("8"),
                        num("7"), num("10"), num("8"))
                .row(8, num("1"), "Earthwork in excavation by mechanical means in all kinds of soil", null,
                        num("1250.5"), "cum", num("245.6", "245.60"), null, null, num("307122.8", "3,07,122.80"), null,
                        "INR Three Lakh Seven Thousand One Hundred Twenty Two and Paise Eighty Only")
                .row(9, num("2"), "Providing and laying cement concrete 1:4:8", null, num("86"), "cum",
                        num("5420", "5,420.00"), null, null, num("466120", "4,66,120.00"), null,
                        "INR Four Lakh Sixty Six Thousand One Hundred Twenty Only")
                .row(10, num("3"), "Reinforcement steel Fe 500D", null, num("12.75"), "MT", num("71250", "71,250.00"),
                        null, null, num("908437.5", "9,08,437.50"), null,
                        "INR Nine Lakh Eight Thousand Four Hundred Thirty Seven and Paise Fifty Only")
                .row(11, "Total in Figures", null, null, null, null, null, null, null,
                        num("1681680.3", "16,81,680.30"), null, "INR Sixteen Lakh Eighty One Thousand Only");
    }

    @Test
    void theEProcurementTemplateIsMappedWholeAndReadsEveryNumber() {
        List<TableRow> rows = templateSheet().rows();

        BoqHeaderDetector.Detection found = BoqHeaderDetector.detect(rows).orElseThrow();

        Map<BoqField, Integer> expected = new EnumMap<>(BoqField.class);
        expected.put(BoqField.ITEM_CODE, 1);
        expected.put(BoqField.DESCRIPTION, 2);
        expected.put(BoqField.QUANTITY, 4);
        expected.put(BoqField.UNIT, 5);
        expected.put(BoqField.RATE, 6);
        expected.put(BoqField.AMOUNT, 9);
        assertThat(found.headerRow()).isEqualTo(6);
        assertThat(found.complete()).isTrue();
        assertThat(found.columns()).isEqualTo(expected);

        Result result = BoqSheetExtractor.extract(rows, false, new Mapping(found.headerRow(), found.columns(), null));

        assertThat(result.lines()).extracting(Line::itemCode).containsExactly("1", "2", "3");
        assertThat(result.lines()).allSatisfy(l -> assertThat(l.flags()).isEmpty());
        assertThat(result.lines()).extracting(Line::rate).map(r -> r.stripTrailingZeros().toPlainString())
                .containsExactly("245.6", "5420", "71250");
        assertThat(result.lines()).extracting(Line::amount).map(a -> a.stripTrailingZeros().toPlainString())
                .containsExactly("307122.8", "466120", "908437.5");
        assertThat(result.lines().get(2).sources().get(BoqField.AMOUNT).ref()).isEqualTo("I10");
        assertThat(result.summary().ignoredRows()).as("the column-number row").containsExactly(7);
        assertThat(result.summary().totals()).isEqualTo(1);
        assertThat(result.summary().warnings()).isEmpty();
    }

    @Test
    void rateAndAmountHeadersMatchOnAWholeWord() {
        assertThat(BoqHeaderDetector.fieldOf("Estimated Rate in Rs. P")).contains(BoqField.RATE);
        assertThat(BoqHeaderDetector.fieldOf("Quoted Rate (Rs.)")).contains(BoqField.RATE);
        assertThat(BoqHeaderDetector.fieldOf("Basic rate in figures")).contains(BoqField.RATE);
        assertThat(BoqHeaderDetector.fieldOf("Schedule Rates 2025")).contains(BoqField.RATE);
        assertThat(BoqHeaderDetector.fieldOf("Rate inclusive of taxes")).contains(BoqField.RATE);
        assertThat(BoqHeaderDetector.fieldOf("Rate excluding GST")).contains(BoqField.RATE);
        assertThat(BoqHeaderDetector.fieldOf("TOTAL AMOUNT With Taxes in Rs. P")).contains(BoqField.AMOUNT);
        assertThat(BoqHeaderDetector.fieldOf("Total Amount Without Taxes")).contains(BoqField.AMOUNT);
        assertThat(BoqHeaderDetector.fieldOf("Estimated Amount")).contains(BoqField.AMOUNT);
        assertThat(BoqHeaderDetector.fieldOf("Total Amt.")).contains(BoqField.AMOUNT);
    }

    @Test
    void rateLikeHeadersThatAreNotTheItemRateMapToNothing() {
        assertThat(BoqHeaderDetector.fieldOf("TOTAL AMOUNT In Words")).isEmpty();
        assertThat(BoqHeaderDetector.fieldOf("Basic Rate In Words")).isEmpty();
        assertThat(BoqHeaderDetector.fieldOf("GST rate (%)")).isEmpty();
        assertThat(BoqHeaderDetector.fieldOf("Tax rate percent")).isEmpty();
        assertThat(BoqHeaderDetector.fieldOf("Rate of GST")).isEmpty();
        assertThat(BoqHeaderDetector.fieldOf("IGST Rate")).isEmpty();
        assertThat(BoqHeaderDetector.fieldOf("Separate remarks")).isEmpty();
        assertThat(BoqHeaderDetector.fieldOf("Corporate reference")).isEmpty();
    }

    @Test
    void theLeftMostRateAndAmountWinWhenTheTemplateRepeatsThem() {
        List<TableRow> rows = new BoqRows("BoQ", 0)
                .row(1, "Item", "Description", "Unit", "Qty", "Estimated Rate", "Quoted Rate", "Estimated Amount",
                        "Quoted Amount")
                .rows();

        Map<BoqField, Integer> columns = BoqHeaderDetector.detect(rows).orElseThrow().columns();

        assertThat(columns.get(BoqField.RATE)).isEqualTo(5);
        assertThat(columns.get(BoqField.AMOUNT)).isEqualTo(7);
    }

    @Test
    void aColumnNumberRowIsKnownInColumnOrderAndWhenItStopsCounting() {
        assertThat(BoqSheetExtractor.isColumnNumberRow(row(num("1"), num("2"), num("3"), num("4"), num("5"))))
                .isTrue();
        assertThat(BoqSheetExtractor.isColumnNumberRow(row(num("1"), num("2"), num("3"), num("4"), num("7"),
                num("6"), num("5")))).as("a template whose later columns are calculated").isTrue();
        assertThat(BoqSheetExtractor.isColumnNumberRow(row("(1)", "(2)", "(3)", "(4)"))).as("numbers as text")
                .isTrue();
        assertThat(BoqSheetExtractor.isColumnNumberRow(row("1", "2", "3", null, "5"))).isTrue();
    }

    @Test
    void anItemOrAShortNumberRowIsNotAColumnNumberRow() {
        assertThat(BoqSheetExtractor.isColumnNumberRow(row(num("1"), "Earthwork", num("2"), num("3"))))
                .as("an item has a text description").isFalse();
        assertThat(BoqSheetExtractor.isColumnNumberRow(row(num("1"), num("2"), num("3.5"), num("4"))))
                .as("a fraction").isFalse();
        assertThat(BoqSheetExtractor.isColumnNumberRow(row(num("2"), num("3"), num("4"), num("5"))))
                .as("does not start at 1").isFalse();
        assertThat(BoqSheetExtractor.isColumnNumberRow(row(num("1"), num("2")))).as("two cells").isFalse();
        assertThat(BoqSheetExtractor.isColumnNumberRow(row(num("1"), num("5"), num("120"), num("600"))))
                .as("an item with no description").isFalse();
    }

    private static TableRow row(Object... values) {
        return new BoqRows("BoQ", 0).row(1, values).rows().get(0);
    }
}

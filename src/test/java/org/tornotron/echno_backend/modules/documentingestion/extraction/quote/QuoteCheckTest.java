package org.tornotron.echno_backend.modules.documentingestion.extraction.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

/** The quote check of design section 8.3, on synthetic pages. */
class QuoteCheckTest {

    static final PageText TEXT_PAGE = new PageText(3, "TEXT", 595.0, 842.0, List.of(
            line("Time allowed for completion of", 0.1, 0.30, 0.6, 0.32),
            line("the work:   twelve (12) months", 0.1, 0.33, 0.7, 0.35),
            line("Earnest money: Rs. 80,000.00", 0.1, 0.50, 0.5, 0.52)), "OK", null, "LATIN", true);
    static final PageText OCR_PAGE = new PageText(4, "OCR", 595.0, 842.0, List.of(
            new PageText.Line("Perfonnance security: 5% of the contract value", List.of(0.1, 0.2, 0.8, 0.22), 0.71)),
            "OK", null, null, true);
    static final ChunkPages CHUNK = new ChunkPages(List.of(TEXT_PAGE, OCR_PAGE));

    @Test
    void aQuoteAcrossTwoLinesIsFoundWithWhitespaceAndCaseNormalizedAndBoxedOverBoth() {
        QuoteCheck.Match m = QuoteCheck.check(CHUNK, 3, "completion of the work: Twelve (12) months");

        assertThat(m.verified()).isTrue();
        assertThat(m.box()).containsExactly(0.1, 0.30, 0.7, 0.35);
        assertThat(m.ocr()).isFalse();
    }

    @Test
    void anInventedQuoteIsRefused() {
        QuoteCheck.Match m = QuoteCheck.check(CHUNK, 3, "Earnest money: Rs. 8,00,000.00");

        assertThat(m.verified()).isFalse();
        assertThat(m.box()).isNull();
        assertThat(m.failure()).contains("not found on page 3");
    }

    @Test
    void aPageOutsideTheChunkIsRefusedEvenWhenTheQuoteIsOnAnotherPage() {
        QuoteCheck.Match m = QuoteCheck.check(CHUNK, 9, "Earnest money: Rs. 80,000.00");

        assertThat(m.verified()).isFalse();
        assertThat(m.failure()).contains("Page 9 was not among the pages sent");
    }

    @Test
    void onAnOcrPageASmallEditDistanceIsAllowedAndOnATextPageItIsNot() {
        assertThat(QuoteCheck.check(CHUNK, 4, "Performance security: 5% of the contract value").verified()).isTrue();
        assertThat(QuoteCheck.check(CHUNK, 4, "Performance guarantee: 10% of the tender value").verified()).isFalse();
        assertThat(QuoteCheck.check(CHUNK, 3, "Earnest rnoney: Rs. 80,000.00").verified())
                .as("text pages match exactly").isFalse();
    }

    @Test
    void aNumberOrDateInTheValueMustBeInItsQuote() {
        assertThat(QuotedNumbers.missing("80000", "Earnest money: Rs. 80,000.00")).isEmpty();
        assertThat(QuotedNumbers.missing("Rs. 1,23,456/-", "amount of 123456.00")).isEmpty();
        assertThat(QuotedNumbers.missing("8,00,000", "Earnest money: Rs. 80,000.00")).containsExactly("800000");
        assertThat(QuotedNumbers.missing("2026-11-30", "Last date 30.11.2026 at 15:00")).isEmpty();
        assertThat(QuotedNumbers.missing("2026-11-30", "on 30th November 2026")).isEmpty();
        assertThat(QuotedNumbers.missing("2026-12-30", "on 30th November 2026")).containsExactly("12");
        assertThat(QuotedNumbers.missing("1.25%", "at 125% of the rate")).containsExactly("1.25");
        assertThat(QuotedNumbers.missing(12, "twelve (12) months")).isEmpty();
        assertThat(QuotedNumbers.missing("twelve months", "twelve (12) months")).isEmpty();
    }

    @Test
    void aCandidateDropsAValueWithoutItsNumbersAndFlagsAnUnverifiedQuote() {
        ExtractContext context = new ExtractContext(UUID.randomUUID(), 7L, UUID.randomUUID(), UUID.randomUUID(), "GCC.pdf",
                null, "a".repeat(64), null, "GCC", 3, 4, CHUNK.pages(), null, Map.of());

        ProposedCandidate c = QuotedCandidates.build(context, CHUNK, CandidateKind.CONTRACT_TERM, "emd", List.of(
                new QuotedCandidates.LlmValue("emd", "Rs. 80,000.00", 3, "Earnest money: Rs. 80,000.00"),
                new QuotedCandidates.LlmValue("completion", "18 months", 3, "twelve (12) months"),
                new QuotedCandidates.LlmValue("security", "5%", 4, "Performance security: 5% of the contract value"),
                new QuotedCandidates.LlmValue("validity", "90 days", 3, "Bid validity: 90 days")));

        assertThat(c.payload()).containsOnlyKeys("emd", "security", "validity");
        assertThat(c.sources()).extracting(ProposedCandidate.ProposedSource::field, ProposedCandidate.ProposedSource::quoteVerified)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("emd", true), org.assertj.core.groups.Tuple.tuple("security", true),
                        org.assertj.core.groups.Tuple.tuple("validity", false));
        assertThat(c.flags()).extracting(f -> f.code())
                .containsExactly(QuotedCandidates.QUOTE_NOT_FOUND, QuotedCandidates.OCR_PAGE);
        assertThat(c.method()).isEqualTo(ExtractionMethod.OCR_LLM);
    }

    static PageText.Line line(String text, double x0, double y0, double x1, double y1) {
        return new PageText.Line(text, List.of(x0, y0, x1, y1), null);
    }
}

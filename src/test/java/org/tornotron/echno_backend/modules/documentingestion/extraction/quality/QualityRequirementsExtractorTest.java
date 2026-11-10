package org.tornotron.echno_backend.modules.documentingestion.extraction.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.InvalidAnswerException;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

class QualityRequirementsExtractorTest {

    /** The ticket's sentence, with the applicable standard named. */
    static final String M25 = "Concrete shall be M25 and shall comply with IS 456:2000.";

    final QualityRequirementsExtractor extractor = new QualityRequirementsExtractor();

    static PageText page(int n, String... lines) {
        List<PageText.Line> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            out.add(new PageText.Line(lines[i], List.of(0.1, 0.1 * i, 0.9, 0.1 * i + 0.05), null));
        }
        return new PageText(n, "TEXT", 595.0, 842.0, out, "OK", null, "LATIN", true);
    }

    final ChunkPages chunk = new ChunkPages(List.of(
            page(1, "TECHNICAL SPECIFICATIONS", "4.1 " + M25),
            page(2, "5.2 Bricks shall be of class designation 7.5 conforming to IS 1077:1992.",
                    "5.3 Compressive strength shall be tested on 10 bricks from every lot of 50,000 and the average",
                    "shall be not less than 7.5 N/mm2.",
                    "5.4 Masonry shall be inspected by the Engineer before plastering. Hold point.")));
    final ExtractContext context = new ExtractContext(UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
            "Specification.pdf", DocumentFileType.PDF, "a".repeat(64), UUID.randomUUID(), "SPECIFICATION", 1, 2,
            chunk.pages(), null, Map.of());

    @Test
    void readsSpecificationsAndThePromptForbidsValuesFromKnowledge() {
        assertThat(extractor.segmentClasses()).containsExactly(DocClass.SPECIFICATION);
        assertThat(extractor.prompt().system())
                .contains("Never supply a frequency or an acceptance criterion from your own knowledge")
                .contains("Never name a standard the pages do not print");
        for (String field : QualityFields.ALL) {
            assertThat(extractor.prompt().system()).contains(field);
        }
        assertThat(extractor.userPrompt(context, chunk)).startsWith("[page 1]\nTECHNICAL SPECIFICATIONS");
    }

    @Test
    void theM25SentenceGivesActivityMaterialGradeAndStandardWithQuotesAndLeavesFrequencyAndAcceptanceEmpty() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"requirements": [{"activity": "Concrete", "material": "Concrete", "grade": "M25",
                  "standards": ["IS 456:2000"], "page": 1, "quote": "4.1 Concrete shall be M25 and shall comply with IS 456:2000."}]}
                """).candidates();

        ProposedCandidate c = single(out);
        assertThat(c.kind()).isEqualTo(CandidateKind.QUALITY_REQUIREMENT);
        assertThat(c.naturalKey()).isEqualTo("Concrete");
        assertThat(c.payload()).containsEntry(QualityFields.ACTIVITY, "Concrete")
                .containsEntry(QualityFields.MATERIAL, "Concrete").containsEntry(QualityFields.GRADE, "M25")
                .containsEntry(QualityFields.STANDARDS, List.of("IS 456:2000"))
                .doesNotContainKeys(QualityFields.FREQUENCY, QualityFields.ACCEPTANCE_CRITERION, QualityFields.TEST);
        assertThat(c.sources()).extracting(ProposedCandidate.ProposedSource::field).containsExactlyInAnyOrder(
                QualityFields.ACTIVITY, QualityFields.MATERIAL, QualityFields.GRADE, QualityFields.STANDARDS);
        assertThat(c.sources()).allSatisfy(s -> {
            assertThat(s.quote()).contains("M25");
            assertThat(s.quoteVerified()).isTrue();
            assertThat(s.page()).isEqualTo(1);
        });
        assertThat(c.flags()).extracting(CandidateFlag::code).containsExactlyInAnyOrder(
                QualityFields.FREQUENCY_NOT_STATED, QualityFields.ACCEPTANCE_NOT_STATED);
        assertThat(c.flags()).allSatisfy(f -> assertThat(f.message()).contains("does not state"));
    }

    @Test
    void aFrequencyOrAcceptanceCriterionTheQuoteDoesNotPrintIsLeftOutAndFlagged() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"requirements": [{"material": "Concrete", "grade": "M25", "test": "Slump",
                  "frequency": "one sample per 50 cubic metres", "acceptance_criterion": "as per Table 11",
                  "page": 1, "quote": "Concrete shall be M25 and shall comply with IS 456:2000.",
                  "field_quotes": {"test": "Concrete shall be M25"}}]}
                """).candidates();

        ProposedCandidate c = single(out);
        assertThat(c.payload()).doesNotContainKeys(QualityFields.FREQUENCY, QualityFields.ACCEPTANCE_CRITERION);
        assertThat(c.payload()).as("a test the quote does not name is not supplied from what concrete usually needs")
                .doesNotContainKey(QualityFields.TEST);
        assertThat(c.flags()).extracting(CandidateFlag::code).contains(QualityFields.FREQUENCY_NOT_STATED,
                QualityFields.ACCEPTANCE_NOT_STATED);
        assertThat(c.flags()).filteredOn(f -> f.code().equals(QualityFields.FREQUENCY_NOT_STATED)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("not printed in the quote"));
    }

    @Test
    void aStatedFrequencyAndAcceptanceCriterionAreKeptWithTheirOwnQuotes() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"requirements": [{"activity": "Brick masonry", "material": "Bricks", "grade": "class designation 7.5",
                  "standards": ["IS 1077:1992", "IS 3495"], "test": "Compressive strength",
                  "frequency": "10 bricks from every lot of 50,000",
                  "acceptance_criterion": "the average shall be not less than 7.5 N/mm2",
                  "hold_or_witness_point": "Hold point", "required_documents": ["Test certificate"],
                  "page": 2, "quote": "5.2 Bricks shall be of class designation 7.5 conforming to IS 1077:1992.",
                  "field_quotes": {"test": "Compressive strength shall be tested on 10 bricks from every lot of 50,000",
                    "frequency": "Compressive strength shall be tested on 10 bricks from every lot of 50,000",
                    "acceptance_criterion": "the average shall be not less than 7.5 N/mm2",
                    "hold_or_witness_point": "Masonry shall be inspected by the Engineer before plastering. Hold point."}}]}
                """).candidates();

        ProposedCandidate c = single(out);
        assertThat(c.payload()).containsEntry(QualityFields.FREQUENCY, "10 bricks from every lot of 50,000")
                .containsEntry(QualityFields.ACCEPTANCE_CRITERION, "the average shall be not less than 7.5 N/mm2")
                .containsEntry(QualityFields.HOLD_OR_WITNESS_POINT, "Hold point")
                .as("a standard the quote does not print is not kept")
                .containsEntry(QualityFields.STANDARDS, List.of("IS 1077:1992"));
        assertThat(c.payload()).as("a required document its quote does not print is left out")
                .doesNotContainKey(QualityFields.REQUIRED_DOCUMENTS)
                .containsEntry(QualityFields.TEST, "Compressive strength");
        assertThat(c.flags()).extracting(CandidateFlag::code)
                .doesNotContain(QualityFields.FREQUENCY_NOT_STATED, QualityFields.ACCEPTANCE_NOT_STATED);
        assertThat(source(c, QualityFields.ACCEPTANCE_CRITERION).quoteVerified()).isTrue();
        assertThat(source(c, QualityFields.FREQUENCY).quote()).contains("every lot of 50,000");
    }

    @Test
    void standardsComeFromTheQuoteByPatternEvenWhenTheModelGivesNone() {
        ProposedCandidate c = single(extractor.read(context, chunk, """
                {"requirements": [{"material": "Concrete", "standards": [], "page": 1,
                  "quote": "Concrete shall be M25 and shall comply with IS 456:2000."}]}
                """).candidates());
        assertThat(c.payload()).containsEntry(QualityFields.STANDARDS, List.of("IS 456:2000"));

        ProposedCandidate vague = single(extractor.read(context, chunk, """
                {"requirements": [{"material": "Concrete", "standards": ["IS xxxx", "IS 456:2000"], "page": 1,
                  "quote": "Concrete shall be M25"}]}
                """).candidates());
        assertThat(vague.payload()).as("no standard the quote does not print").doesNotContainKey(QualityFields.STANDARDS);
    }

    /** Design section 5: zero values with no quote. Every value proposed carries a non-blank quote. */
    @Test
    void noValueIsProposedWithoutAQuote() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"requirements": [
                  {"activity": "Concrete", "material": "Concrete", "grade": "M25", "standards": ["IS 456:2000"],
                   "frequency": "daily", "acceptance_criterion": "no honeycombing", "page": 1,
                   "quote": "Concrete shall be M25 and shall comply with IS 456:2000."},
                  {"material": "Bricks", "grade": "class designation 7.5", "test": "Water absorption",
                   "frequency": "every lot", "page": 2},
                  {"material": "Cement", "grade": "OPC 43", "page": 2, "quote": "Cement shall be OPC 43 grade."},
                  {"activity": "Masonry", "hold_or_witness_point": "Hold point", "page": 2,
                   "quote": "5.4 Masonry shall be inspected by the Engineer before plastering. Hold point."}
                ]}""").candidates();

        assertThat(out).isNotEmpty();
        for (ProposedCandidate c : out) {
            for (String field : c.payload().keySet()) {
                assertThat(c.sources()).as("%s of %s has a source", field, c.naturalKey())
                        .anySatisfy(s -> {
                            assertThat(s.field()).isEqualTo(field);
                            assertThat(s.quote()).isNotBlank();
                        });
            }
            for (String field : QualityFields.QUOTE_ONLY) {
                if (c.payload().containsKey(field)) {
                    assertThat(source(c, field).quoteVerified()).as("a quote-only value's quote is on its page").isTrue();
                } else {
                    assertThat(c.flags()).extracting(CandidateFlag::code).contains(QualityFields.notStatedFlag(field));
                }
            }
        }
        assertThat(out).extracting(ProposedCandidate::naturalKey).doesNotContain("Bricks")
                .as("the cement grade's number is in its quote; the quote is not on the page, so it is flagged")
                .contains("Cement", "Concrete", "Masonry");
        assertThat(out).filteredOn(c -> c.naturalKey().equals("Concrete")).singleElement()
                .satisfies(c -> assertThat(c.payload()).doesNotContainKeys(QualityFields.FREQUENCY,
                        QualityFields.ACCEPTANCE_CRITERION));
        assertThat(out).filteredOn(c -> c.naturalKey().equals("Cement")).singleElement()
                .satisfies(c -> assertThat(c.flags()).extracting(CandidateFlag::code).contains("QUOTE_NOT_FOUND"));
    }

    @Test
    void twoRequirementsOfOneMaterialKeepBothAndAnAnswerWithNoListIsInvalid() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"requirements": [
                  {"material": "Concrete", "grade": "M25", "page": 1, "quote": "Concrete shall be M25"},
                  {"material": "Concrete", "standards": ["IS 456:2000"], "page": 1, "quote": "Concrete shall be M25 and shall comply with IS 456:2000."},
                  {"page": 1, "quote": "TECHNICAL SPECIFICATIONS"}
                ]}""").candidates();
        assertThat(out).extracting(ProposedCandidate::naturalKey).containsExactly("Concrete", "Concrete#2");

        assertThatThrownBy(() -> extractor.read(context, chunk, "{\"items\": []}"))
                .isInstanceOf(InvalidAnswerException.class);
    }

    static ProposedCandidate single(List<ProposedCandidate> out) {
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    static ProposedCandidate.ProposedSource source(ProposedCandidate c, String field) {
        return c.sources().stream().filter(s -> s.field().equals(field)).findFirst().orElseThrow();
    }
}

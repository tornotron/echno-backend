package org.tornotron.echno_backend.modules.documentingestion.extraction.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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

class ContractTermsExtractorTest {

    final ContractTermsExtractor extractor = new ContractTermsExtractor(mock(ContractTermLinker.class));

    static PageText page(int n, String... lines) {
        List<PageText.Line> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            out.add(new PageText.Line(lines[i], List.of(0.1, 0.1 * i, 0.9, 0.1 * i + 0.05), null));
        }
        return new PageText(n, "TEXT", 595.0, 842.0, out, "OK", null, "LATIN", true);
    }

    final ChunkPages chunk = new ChunkPages(List.of(
            page(1, "10. Notice to commence",
                    "The Contractor shall submit the programme within 15 days of the date of the notice to commence."),
            page(2, "47.1 Liquidated damages shall be 0.5% of the Contract Price per week of delay.",
                    "47.2 The total liquidated damages shall not exceed 10% of the Contract Price.",
                    "52.1 The Contractor shall furnish a Performance Security within 21 days of the Letter of Acceptance.",
                    "52.2 The Contractor shall furnish the insurance policies within 21 days of the Letter of Acceptance.")));
    final ExtractContext context = new ExtractContext(UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
            "Conditions.pdf", DocumentFileType.PDF, "a".repeat(64), UUID.randomUUID(), "GCC", 1, 2, chunk.pages(), null,
            Map.of());

    @Test
    void readsTheConditionsOfContractAndAsksForEveryTerm() {
        assertThat(extractor.segmentClasses()).containsExactlyInAnyOrder(DocClass.GCC, DocClass.SCC, DocClass.CONTRACT_DATA);
        assertThat(ContractTerm.values()).hasSize(19);
        for (ContractTerm t : ContractTerm.values()) {
            assertThat(extractor.prompt().system()).contains(t.key() + ": " + t.meaning());
        }
        assertThat(extractor.prompt().system()).doesNotContain("%s").contains("0.5% of the Contract Price");
        assertThat(extractor.userPrompt(context, chunk)).contains("[page 1]\n10. Notice to commence").contains("[page 2]\n");
    }

    @Test
    void termsAreKeyedByNameWithValueAndClauseEachQuoteChecked() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"terms": [
                  {"term": "LD rate", "value": "0.5% of the Contract Price per week of delay", "clause": "Clause 47.1", "page": 2, "quote": "47.1 Liquidated damages shall be 0.5% of the Contract Price per week of delay."},
                  {"term": "ld_cap", "value": "15% of the Contract Price", "clause": "47.2", "page": 2, "quote": "47.2 The total liquidated damages shall not exceed 10% of the Contract Price."},
                  {"term": "defects_liability_period", "value": "12 months", "clause": "35", "page": 2, "quote": "35 The defects liability period is 12 months."},
                  {"term": "bidder_rating", "value": "A", "page": 1, "quote": "10. Notice to commence"},
                  {"term": "ld_rate", "value": "1%", "page": 2, "quote": "1%"}
                ]}""").candidates();

        assertThat(out).extracting(ProposedCandidate::naturalKey).containsExactly("ld_rate", "dlp");
        ProposedCandidate ld = out.get(0);
        assertThat(ld.kind()).isEqualTo(CandidateKind.CONTRACT_TERM);
        assertThat(ld.payload()).containsEntry(ContractTerm.VALUE, "0.5% of the Contract Price per week of delay")
                .containsEntry(ContractTerm.CLAUSE, "47.1");
        assertThat(ld.flags()).isEmpty();
        assertThat(ld.sources()).extracting(ProposedCandidate.ProposedSource::field)
                .containsExactlyInAnyOrder(ContractTerm.VALUE, ContractTerm.CLAUSE);
        assertThat(out.get(1).flags()).extracting(CandidateFlag::code).contains("QUOTE_NOT_FOUND");
    }

    @Test
    void obligationsAreKeyedByClauseAndDeadlineAndNeedADeadline() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"terms": [], "obligations": [
                  {"clause": "10", "responsible_party": "Contractor", "trigger": "the date of the notice to commence", "deadline": "15 days", "required_document": "programme", "topic": "programme", "page": 1, "quote": "The Contractor shall submit the programme within 15 days of the date of the notice to commence.", "clause_quote": "10. Notice to commence"},
                  {"clause": "GCC 52.1", "responsible_party": "Contractor", "deadline": "21 days", "topic": "performance security", "page": 2, "quote": "52.1 The Contractor shall furnish a Performance Security within 21 days of the Letter of Acceptance."},
                  {"clause": "52.1", "responsible_party": "Contractor", "deadline": "21 days", "topic": "insurance", "page": 2, "quote": "52.2 The Contractor shall furnish the insurance policies within 21 days of the Letter of Acceptance."},
                  {"clause": "53", "responsible_party": "Employer", "topic": "site possession", "page": 2, "quote": "52.1 The Contractor"},
                  {"clause": "54", "deadline": "30 days", "page": 2, "quote": "52.1 The Contractor shall furnish a Performance Security within 21 days"}
                ]}""").candidates();

        assertThat(out).extracting(ProposedCandidate::naturalKey).containsExactly("10|15 days", "52.1|21 days", "|21 days");
        ProposedCandidate first = out.get(0);
        assertThat(first.kind()).isEqualTo(CandidateKind.OBLIGATION);
        assertThat(first.payload()).containsEntry(Obligations.CLAUSE, "10").containsEntry(Obligations.DEADLINE, "15 days")
                .containsEntry(Obligations.RESPONSIBLE_PARTY, "Contractor").containsEntry(Obligations.TOPIC, "programme");
        assertThat(first.flags()).isEmpty();
        assertThat(out.get(2).payload()).as("52.1 is not in the quote of 52.2, so the clause is dropped")
                .doesNotContainKey(Obligations.CLAUSE);
    }

    @Test
    void twoObligationsOfOneClauseAndDeadlineInAChunkAreBothKept() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"obligations": [
                  {"clause": "52", "deadline": "21 days", "topic": "performance security", "page": 2, "quote": "52.1 The Contractor shall furnish a Performance Security within 21 days of the Letter of Acceptance."},
                  {"clause": "52", "deadline": "21 days", "topic": "insurance", "page": 2, "quote": "52.2 The Contractor shall furnish the insurance policies within 21 days of the Letter of Acceptance."}
                ]}""").candidates();

        assertThat(out).extracting(ProposedCandidate::naturalKey).containsExactly("52|21 days", "52|21 days#2");
    }

    @Test
    void anAnswerWithNeitherListIsInvalid() {
        assertThatThrownBy(() -> extractor.read(context, chunk, "{\"fields\": []}"))
                .isInstanceOf(InvalidAnswerException.class);
    }

    @Test
    void termNamesAndClausesAndDeadlinesRead() {
        assertThat(ContractTerm.of("Mobilisation")).contains(ContractTerm.MOBILIZATION);
        assertThat(ContractTerm.of("extension-of-time")).contains(ContractTerm.EXTENSION_OF_TIME);
        assertThat(ContractTerm.of("Defects Liability Period")).contains(ContractTerm.DLP);
        assertThat(ContractTerm.of("bidder rating")).isEmpty();
        assertThat(Obligations.clause("Sub-Clause 4.2")).isEqualTo("4.2");
        assertThat(Obligations.clause("GCC Clause 47.1.")).isEqualTo("47.1");
        assertThat(Obligations.clause("Appendix A")).isEqualTo("Appendix A");
        assertThat(Obligations.clause("Clause")).isNull();
        assertThat(Obligations.clause("Cl. 5")).isEqualTo("5");
        assertThat(Obligations.clause("Nominated works")).isEqualTo("Nominated works");
        assertThat(Obligations.days("15 days")).contains(15);
        assertThat(Obligations.days("within 28 days of the notice")).contains(28);
        assertThat(Obligations.days("4 weeks")).contains(28);
        assertThat(Obligations.days("21 (twenty one) days")).contains(21);
        assertThat(Obligations.days("7 working days")).isEmpty();
        assertThat(Obligations.days("2 months")).isEmpty();
        assertThat(Obligations.days("before the start of work")).isEmpty();
    }
}

package org.tornotron.echno_backend.modules.documentingestion.extraction.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.InvalidAnswerException;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

class ProjectSetupExtractorTest {

    final ProjectSetupExtractor extractor = new ProjectSetupExtractor();

    static PageText page(int n, String method, String... lines) {
        List<PageText.Line> out = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            out.add(new PageText.Line(lines[i], List.of(0.1, 0.1 * i, 0.9, 0.1 * i + 0.05), null));
        }
        return new PageText(n, method, 595.0, 842.0, out, "OK", null, "TEXT".equals(method) ? "LATIN" : null, true);
    }

    final ChunkPages chunk = new ChunkPages(List.of(
            page(1, "TEXT", "Name of Work: Library block, Hillview", "Estimated Cost: Rs. 4,20,00,000"),
            page(2, "OCR", "Earnest Money Deposit: Rs. 8,40,000")));
    final ExtractContext context = new ExtractContext(UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
            "NIT.pdf", DocumentFileType.PDF, "a".repeat(64), UUID.randomUUID(), "NIT", 1, 2, chunk.pages(), null, Map.of());

    @Test
    void readsTheNoticeClassesAndAsksForEveryField() {
        assertThat(extractor.segmentClasses()).containsExactlyInAnyOrder(DocClass.NIT, DocClass.ITB, DocClass.SCOPE);
        for (ProjectField f : ProjectField.values()) {
            assertThat(extractor.prompt().system()).contains(f.key() + ": " + f.meaning());
        }
        assertThat(extractor.prompt().system()).doesNotContain("%s");
        assertThat(extractor.userPrompt(context, chunk)).contains("[page 1]\nName of Work").contains("[page 2]\n");
    }

    @Test
    void eachFieldIsOneCandidateKeyedByItsNameWithItsQuoteChecked() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                Here you are: {"fields": [
                  {"field": "project_name", "value": "Library block, Hillview", "page": 1, "quote": "Name of Work: Library block, Hillview"},
                  {"field": "Estimated Value", "value": "Rs. 4,20,00,000", "page": 1, "quote": "Estimated Cost: Rs. 4,20,00,000"},
                  {"field": "emd", "value": "Rs. 8,40,000", "page": 2, "quote": "Earnest Money Deposit: Rs. 8,40,000"},
                  {"field": "client", "value": "Northfield Trust", "page": 1, "quote": "issued by Northfield Trust"},
                  {"field": "tender_fee", "value": "Rs. 5,000", "page": 1, "quote": "Name of Work: Library block, Hillview"},
                  {"field": "bidder_rating", "value": "A", "page": 1, "quote": "Name of Work"},
                  {"field": "project_name", "value": "Another name", "page": 1, "quote": "Name of Work"}
                ]}""").candidates();

        assertThat(out).extracting(ProposedCandidate::naturalKey).containsExactly("project_name", "estimated_value", "emd",
                "client");
        assertThat(out.get(0).payload()).containsEntry(ProjectField.VALUE, "Library block, Hillview");
        assertThat(out.get(0).flags()).isEmpty();
        assertThat(out.get(0).sources()).singleElement().satisfies(s -> assertThat(s.box()).isNotNull());
        assertThat(out.get(2).method()).isEqualTo(ExtractionMethod.OCR_LLM);
        assertThat(out.get(3).flags()).extracting(CandidateFlag::code).contains("QUOTE_NOT_FOUND");
    }

    @Test
    void anAnswerWithNoFieldsListIsInvalid() {
        assertThatThrownBy(() -> extractor.read(context, chunk, "{\"values\": []}"))
                .isInstanceOf(InvalidAnswerException.class);
    }
}

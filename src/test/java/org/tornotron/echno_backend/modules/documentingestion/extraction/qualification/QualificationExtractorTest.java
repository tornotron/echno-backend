package org.tornotron.echno_backend.modules.documentingestion.extraction.qualification;

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
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.InvalidAnswerException;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

class QualificationExtractorTest {

    final QualificationExtractor extractor = new QualificationExtractor();

    static PageText page(int n, String... lines) {
        List<PageText.Line> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            out.add(new PageText.Line(lines[i], List.of(0.1, 0.1 * i, 0.9, 0.1 * i + 0.05), null));
        }
        return new PageText(n, "TEXT", 595.0, 842.0, out, "OK", null, "LATIN", true);
    }

    final ChunkPages chunk = new ChunkPages(List.of(
            page(1, "3.1 The bidder shall have an average annual turnover of Rs. 40 crore over the last 3 financial years.",
                    "3.2 The bidder shall have completed three similar works, each of value not less than Rs. 12 crore,",
                    "or two similar works, each of value not less than Rs. 18 crore."),
            page(2, "3.3 The bidder shall have at least 5 years of experience in building works.",
                    "3.4 Key personnel: a project manager, a planning engineer and a safety officer.")));
    final ExtractContext context = new ExtractContext(UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
            "Eligibility.pdf", DocumentFileType.PDF, "a".repeat(64), UUID.randomUUID(), "ELIGIBILITY", 1, 2,
            chunk.pages(), null, Map.of());

    @Test
    void readsTheThreeQualificationClassesOnlyWhileTendering() {
        assertThat(extractor.segmentClasses()).containsExactlyInAnyOrder(DocClass.NIT, DocClass.ITB, DocClass.ELIGIBILITY);
        assertThat(extractor.readsAtStage(PackageStage.TENDER)).isTrue();
        assertThat(extractor.readsAtStage(PackageStage.AWARDED)).isFalse();
        for (QualificationRequirementType t : QualificationRequirementType.values()) {
            assertThat(extractor.prompt().system()).contains(t.key() + ": " + t.meaning());
        }
        assertThat(extractor.prompt().system()).doesNotContain("%s").contains("Rs. 25 crore");
        assertThat(extractor.userPrompt(context, chunk)).contains("[page 1]\n3.1 The bidder").contains("[page 2]\n");
    }

    @Test
    void eachRequirementIsKeyedByTypeAndValueWithItsFieldsQuoteChecked() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"requirements": [
                  {"requirement": "turnover", "value": "Rs. 40 crore", "period": "average over the last 3 financial years", "page": 1, "quote": "3.1 The bidder shall have an average annual turnover of Rs. 40 crore over the last 3 financial years."},
                  {"requirement": "similar_projects", "value": "three", "min_value": "Rs. 12 crore", "page": 1, "quote": "3.2 The bidder shall have completed three similar works, each of value not less than Rs. 12 crore,"},
                  {"requirement": "similar_projects", "value": "two", "min_value": "Rs. 18 crore", "page": 1, "quote": "or two similar works, each of value not less than Rs. 18 crore."},
                  {"requirement": "experience", "value": "7 years", "page": 2, "quote": "3.3 The bidder shall have at least 5 years of experience in building works."},
                  {"requirement": "personnel", "value": "a project manager, a planning engineer and a safety officer", "min_value": "Rs. 1 crore", "page": 2, "quote": "3.4 Key personnel: a project manager, a planning engineer and a safety officer."},
                  {"requirement": "bank_solvency", "value": "Rs. 5 crore", "page": 2, "quote": "Rs. 5 crore"}
                ]}""").candidates();

        assertThat(out).extracting(ProposedCandidate::naturalKey).containsExactly("turnover|rs-40-crore",
                "similar_projects|three|rs-12-crore", "similar_projects|two|rs-18-crore",
                "personnel|a-project-manager-a-planning-engineer-and-a-safety-officer");
        assertThat(out).as("a number not in its quote drops the value, and with it the requirement")
                .noneMatch(c -> c.naturalKey().startsWith("experience"));
        ProposedCandidate turnover = out.get(0);
        assertThat(turnover.kind()).isEqualTo(CandidateKind.QUALIFICATION_REQUIREMENT);
        assertThat(turnover.payload()).containsEntry("value", "Rs. 40 crore")
                .containsEntry("period", "average over the last 3 financial years");
        assertThat(turnover.flags()).isEmpty();
        assertThat(out.get(1).payload()).containsEntry("value", "three").containsEntry("min_value", "Rs. 12 crore");
        assertThat(out.get(3).payload()).as("personnel carries no minimum value").doesNotContainKey("min_value");
    }

    @Test
    void anInventedQuoteIsFlaggedAndTwoAlternativesThatReadAlikeAreBothKept() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"requirements": [
                  {"requirement": "equipment", "value": "one batching plant", "page": 2, "quote": "one batching plant"},
                  {"requirement": "equipment", "value": "one batching plant", "page": 2, "quote": "one batching plant"}
                ]}""").candidates();

        assertThat(out).extracting(ProposedCandidate::naturalKey)
                .containsExactly("equipment|one-batching-plant", "equipment|one-batching-plant#2");
        assertThat(out.get(0).flags()).extracting(CandidateFlag::code).contains("QUOTE_NOT_FOUND");
    }

    @Test
    void anAnswerWithNoRequirementsListIsRetried() {
        assertThatThrownBy(() -> extractor.read(context, chunk, "{\"terms\": []}"))
                .isInstanceOf(InvalidAnswerException.class);
        assertThat(extractor.read(context, chunk, "{\"requirements\": []}").candidates()).isEmpty();
    }

    @Test
    void theNaturalKeyNamesTheType() {
        assertThat(QualificationRequirementType.ofNaturalKey("similar_projects|3|rs-30-cr#2"))
                .contains(QualificationRequirementType.SIMILAR_PROJECTS);
        assertThat(QualificationRequirementType.ofNaturalKey("bank_solvency|x")).isEmpty();
    }
}

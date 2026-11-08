package org.tornotron.echno_backend.modules.documentingestion.extraction.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

class MilestoneExtractorTest {

    final MilestoneExtractor extractor = new MilestoneExtractor(mock(CandidateWriter.class));

    final ChunkPages chunk = new ChunkPages(List.of(new PageText(4, "TEXT", 595.0, 842.0, List.of(
            new PageText.Line("Completion of block A, Month 6, 40%", List.of(0.1, 0.1, 0.9, 0.15), null),
            new PageText.Line("Completion of block B, Month 9, 60%", List.of(0.1, 0.2, 0.9, 0.25), null)),
            "OK", null, "LATIN", true)));
    final ExtractContext context = new ExtractContext(UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
            "Schedule.pdf", DocumentFileType.PDF, "a".repeat(64), UUID.randomUUID(), "MILESTONE_SCHEDULE", 4, 4,
            chunk.pages(), null, Map.of());

    @Test
    void twoMilestonesThatShareANameAreBothKeptAndTheSameOneGivenTwiceIsReadOnce() {
        List<ProposedCandidate> out = extractor.read(context, chunk, """
                {"milestones": [
                  {"name": "Completion", "time": "Month 6", "percent": "40%", "page": 4, "quote": "Completion of block A, Month 6, 40%"},
                  {"name": "Completion", "time": "Month 6", "percent": "40%", "page": 4, "quote": "Completion of block A, Month 6, 40%"},
                  {"name": "Completion", "time": "Month 9", "percent": "60%", "page": 4, "quote": "Completion of block B, Month 9, 60%"}
                ]}""").candidates();

        assertThat(out).hasSize(2);
        assertThat(out).extracting(ProposedCandidate::naturalKey).doesNotHaveDuplicates().contains("Completion");
        assertThat(out).extracting(c -> c.payload().get(MilestoneTables.PERCENT)).containsExactly("40%", "60%");
    }
}

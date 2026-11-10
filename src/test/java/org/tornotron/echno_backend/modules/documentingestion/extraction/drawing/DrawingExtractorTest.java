package org.tornotron.echno_backend.modules.documentingestion.extraction.drawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkOutput;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractCompletion;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractPlan;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.InvalidAnswerException;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quote.QuotedCandidates;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TitleBlock;

class DrawingExtractorTest {

    final CandidateWriter writer = mock(CandidateWriter.class);
    final DrawingExtractor extractor = new DrawingExtractor(writer);

    final TitleBlock labelled = TitleBlocks.page(1).row(0.90, 0.80, "DRAWING", "NO.", "RCB-AR-101", "REV", "A").build();
    final TitleBlock unlabelled = TitleBlocks.page(2).row(0.85, 0.80, "RIVERSIDE", "DEPOT")
            .row(0.86, 0.80, "RCB-AR-102", "B").build();
    final TitleBlock portrait = new TitleBlock(3, "SKIPPED", "Not shaped like a drawing sheet.", "NONE", null, null,
            null, List.of(), List.of());
    final TitleBlock failed = new TitleBlock(4, "FAILED", "The crop could not be read.", "NONE", null, null, null,
            List.of(), List.of());

    @Test
    void patternsReadWhatTheyCanAndOnlyTheRestGoesToTheLlmAsTheCropsText() {
        ExtractContext context = pdf(List.of(labelled, unlabelled, portrait, failed));

        ExtractPlan plan = extractor.plan(context);

        assertThat(plan.toSend()).singleElement().satisfies(p -> {
            assertThat(p.page()).isEqualTo(2);
            assertThat(p.text()).as("the crop's text, not the sheet's").isEqualTo("RIVERSIDE DEPOT\nRCB-AR-102 B");
        });
        assertThat(plan.unread()).singleElement().satisfies(u -> {
            assertThat(u.page()).isEqualTo(4);
            assertThat(u.detail()).isEqualTo("The crop could not be read.");
        });
        assertThat(plan.result()).containsEntry("patternSheets", 1).containsEntry("llmSheets", 1)
                .containsEntry("skippedSheets", 1);
        assertThat(extractor.userPrompt(context, new ChunkPages(plan.toSend())))
                .contains("[page 2]").doesNotContain("PLAN OF THE SHEET");
    }

    @Test
    void theLlmAnswerGoesThroughTheQuoteCheckOnTheCrop() {
        ExtractContext context = pdf(List.of(labelled, unlabelled));
        ChunkPages chunk = new ChunkPages(extractor.plan(context).toSend());

        ChunkOutput out = extractor.read(context, chunk, """
                {"drawings": [
                  {"page": 2, "drawing_number": {"value": "RCB-AR-102", "quote": "RCB-AR-102 B"},
                   "revision": {"value": "B", "quote": "RCB-AR-102 B"},
                   "title": {"value": "RIVERSIDE DEPOT", "quote": "RIVERSIDE DEPOT ELEVATIONS"}},
                  {"page": 2, "drawing_number": {"value": "RCB-AR-999", "quote": "RCB-AR-102 B"}},
                  {"page": 9, "drawing_number": {"value": "RCB-AR-103", "quote": "RCB-AR-103"}}
                ]}""");

        assertThat(out.candidates()).as("a number not in its quote is dropped, a page outside the chunk ignored")
                .singleElement().satisfies(c -> {
                    assertThat(c.kind()).isEqualTo(CandidateKind.DRAWING);
                    assertThat(c.naturalKey()).isEqualTo("RCB-AR-102");
                    assertThat(c.method()).isEqualTo(ExtractionMethod.LLM);
                    assertThat(c.payload()).containsEntry(DrawingFields.REVISION, "B")
                            .containsEntry(DrawingFields.SHEET_PAGE, 2)
                            .containsEntry(DrawingFields.CROP_IMAGE, "titleblock/2.webp")
                            .containsEntry(DrawingFields.SOURCE_FORMAT, "PDF");
                    assertThat(c.flags()).extracting(f -> f.code()).as("the title's quote is not on the crop")
                            .containsExactly(QuotedCandidates.QUOTE_NOT_FOUND);
                    assertThat(c.sources()).filteredOn(s -> s.field().equals(DrawingFields.NUMBER)).singleElement()
                            .satisfies(s -> {
                                assertThat(s.quoteVerified()).isTrue();
                                assertThat(s.box()).isNotNull();
                            });
                });
        assertThatThrownBy(() -> extractor.read(context, chunk, "{\"sheets\": []}"))
                .isInstanceOf(InvalidAnswerException.class);
    }

    @Test
    void completeWritesOneDeterministicRowPerPatternSheetWithItsCrop() {
        TitleBlock ocr = TitleBlocks.ocrPage(5).row(0.90, 0.80, "DRAWING", "NO.", "RCB-ST-201").build();
        ExtractContext context = pdf(List.of(labelled, unlabelled, ocr));

        extractor.complete(context, new ExtractCompletion(List.of(), true));

        ArgumentCaptor<ProposedCandidate> written = ArgumentCaptor.forClass(ProposedCandidate.class);
        verify(writer).upsertFromChunk(eq(context.jobId()), eq("sheet-1"), written.capture());
        verify(writer).upsertFromChunk(eq(context.jobId()), eq("sheet-5"), written.capture());
        ProposedCandidate first = written.getAllValues().get(0);
        assertThat(first.method()).isEqualTo(ExtractionMethod.DETERMINISTIC);
        assertThat(first.payload()).containsEntry(DrawingFields.NUMBER, "RCB-AR-101")
                .containsEntry(DrawingFields.REVISION, "A").containsEntry(DrawingFields.CROP_IMAGE, "titleblock/1.webp");
        assertThat(first.sources()).allSatisfy(s -> {
            assertThat(s.page()).isEqualTo(1);
            assertThat(s.quoteVerified()).isTrue();
        });
        assertThat(first.flags()).isEmpty();
        assertThat(written.getAllValues().get(1).flags()).extracting(f -> f.code())
                .containsExactlyInAnyOrder(QuotedCandidates.OCR_PAGE, DrawingFields.NO_REVISION);
    }

    @Test
    void anOutputWithNoTitleBlocksSaysThePagesWereNotRead() {
        ExtractContext context = pdf(null);

        ExtractPlan plan = extractor.plan(context);

        assertThat(plan.toSend()).isEmpty();
        assertThat(plan.unread()).hasSize(2).allSatisfy(u -> {
            assertThat(u.reason()).isEqualTo(UnreadPage.NOT_PARSED);
            assertThat(u.detail()).isEqualTo(DrawingExtractor.TITLE_BLOCK_NOT_READ);
        });
        extractor.complete(context, new ExtractCompletion(plan.unread(), false));
        verify(writer, org.mockito.Mockito.never()).upsertFromChunk(any(), any(), any());
    }

    @Test
    void aDwgIsNamedByItsFileAndFlaggedNotRead() {
        ExtractContext context = new ExtractContext(UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
                "HRV-AR-301_RevB.dwg", DocumentFileType.DWG, null, UUID.randomUUID(), "DRAWING", null, null, List.of(),
                null, Map.of());

        assertThat(extractor.plan(context).toSend()).isEmpty();
        extractor.complete(context, new ExtractCompletion(List.of(), false));

        ArgumentCaptor<ProposedCandidate> written = ArgumentCaptor.forClass(ProposedCandidate.class);
        verify(writer).upsertFromChunk(eq(context.jobId()), eq("file"), written.capture());
        ProposedCandidate c = written.getValue();
        assertThat(c.naturalKey()).isEqualTo("HRV-AR-301");
        assertThat(c.payload()).containsEntry(DrawingFields.NUMBER, "HRV-AR-301").containsEntry(DrawingFields.REVISION, "B")
                .containsEntry(DrawingFields.SOURCE_FORMAT, "DWG");
        assertThat(c.flags()).extracting(f -> f.code())
                .containsExactly(DrawingFields.NOT_READ, DrawingFields.NUMBER_FROM_FILE_NAME);
        assertThat(c.flags().get(0).message()).isEqualTo("Not read, add a PDF or DXF copy.");
        assertThat(c.sources()).allSatisfy(s -> assertThat(s.quote()).isEqualTo("HRV-AR-301_RevB.dwg"));
    }

    static ExtractContext pdf(List<TitleBlock> blocks) {
        List<PageText> pages = new ArrayList<>();
        for (int n : new int[] {1, 2}) {
            pages.add(new PageText(n, "TEXT", 1190.0, 842.0, List.of(new PageText.Line("PLAN OF THE SHEET",
                    List.of(0.1, 0.1, 0.5, 0.12), null)), "OK", null, "LATIN", true));
        }
        return new ExtractContext(UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(), "Drawings.pdf",
                DocumentFileType.PDF, "a".repeat(64), UUID.randomUUID(), "DRAWING", 1, 5, pages, null, Map.of(),
                List.of(), blocks, null);
    }
}

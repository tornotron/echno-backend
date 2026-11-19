package org.tornotron.echno_backend.modules.assistant.ingest;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.tornotron.echno_backend.modules.assistant.AssistantProperties;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private final Chunker chunker = new Chunker(new AssistantProperties());

    @Test
    @DisplayName("Single short paragraph produces exactly one chunk with valid hash")
    void shortSinglePageProducesOneChunk() {
        String text = "Foundation concrete specifications: Grade M25 with slump 100mm.";
        ExtractedDocument doc = new ExtractedDocument(List.of(new ExtractedPage(1, text)));

        List<DocChunk> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(1);
        DocChunk c0 = chunks.get(0);
        assertThat(c0.chunkIndex()).isEqualTo(0);
        assertThat(c0.pageNo()).isEqualTo(1);
        assertThat(c0.charStart()).isEqualTo(0);
        assertThat(c0.charEnd()).isEqualTo(text.length());
        assertThat(c0.content()).isEqualTo(text);
        assertThat(c0.contentHash()).hasSize(64); // SHA-256 in hex
    }

    @Test
    @DisplayName("Long text produces multiple chunks with overlap")
    void longTextProducesMultipleChunksWithOverlap() {
        // Construct paragraphs totaling > 2000 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("Section ").append(i).append(": ")
                    .append("The structural integrity of beam ").append(i)
                    .append(" is verified under standard load testing conditions as per ISO 9001 quality guidelines.\n\n");
        }
        String fullText = sb.toString();
        ExtractedDocument doc = new ExtractedDocument(List.of(new ExtractedPage(1, fullText)));

        List<DocChunk> chunks = chunker.chunk(doc);

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).chunkIndex()).isEqualTo(i);
            assertThat(chunks.get(i).pageNo()).isEqualTo(1);
            assertThat(chunks.get(i).content()).isNotBlank();
            assertThat(chunks.get(i).contentHash()).isNotBlank();
        }

        // Check overlap between consecutive chunks
        DocChunk first = chunks.get(0);
        DocChunk second = chunks.get(1);
        // Overlap means second chunk start < first chunk end
        assertThat(second.charStart()).isLessThan(first.charEnd());
    }

    @Test
    @DisplayName("Multiple pages track page numbers accurately")
    void multiplePagesTrackPageNumbers() {
        ExtractedDocument doc = new ExtractedDocument(List.of(
                new ExtractedPage(1, "Page 1: Project overview and stakeholder roster."),
                new ExtractedPage(2, "Page 2: Bill of quantities and delivery schedule.")
        ));

        List<DocChunk> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).pageNo()).isEqualTo(1);
        assertThat(chunks.get(0).content()).contains("Page 1");
        assertThat(chunks.get(1).pageNo()).isEqualTo(2);
        assertThat(chunks.get(1).content()).contains("Page 2");
    }

    @Test
    @DisplayName("Empty or whitespace pages are ignored")
    void ignoresEmptyPages() {
        ExtractedDocument doc = new ExtractedDocument(List.of(
                new ExtractedPage(1, "   \n\t  "),
                new ExtractedPage(2, "Valid content on page two.")
        ));

        List<DocChunk> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).pageNo()).isEqualTo(2);
        assertThat(chunks.get(0).content()).isEqualTo("Valid content on page two.");
    }
}

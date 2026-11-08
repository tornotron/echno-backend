package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

class ChunkPagesTest {

    @Test
    void pagesGoInChunksOfEightAndAChunkSplitsInHalfDownToOnePage() {
        List<PageText> pages = IntStream.rangeClosed(1, 19).filter(p -> p != 5)
                .mapToObj(p -> new PageText(p, "TEXT", null, null, List.of(), "OK", null, "LATIN", true)).toList();

        List<ChunkPages> chunks = ChunkPages.of(pages, 8);

        assertThat(chunks).extracting(ChunkPages::key).containsExactly("1-9", "10-17", "18-19");
        assertThat(chunks.get(0).describe()).isEqualTo("1-4,6-9");
        assertThat(chunks.get(0).halves()).extracting(ChunkPages::key).containsExactly("1-4", "6-9");
        assertThatThrownBy(() -> chunks.get(2).halves().get(1).halves()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theLedgerFormOfPagesReadsBack() {
        assertThat(ChunkPages.parse("1-4,6,9-10")).containsExactly(1, 2, 3, 4, 6, 9, 10);
        assertThat(ChunkPages.describe(List.of(1, 2, 3, 4, 6, 9, 10))).isEqualTo("1-4,6,9-10");
    }
}

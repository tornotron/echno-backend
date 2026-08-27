package org.tornotron.echno_backend.common.pagination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the promise that makes capping safe to ship ahead of the client work: a capped response
 * never lies about how much there is. Plain JUnit, no Spring context.
 */
class UnpagedResultCapTest {

    @Test
    @DisplayName("a result that fits carries the total and no truncation flag")
    void completeResultIsNotFlagged() {
        Page<String> page = new PageImpl<>(
                List.of("a", "b", "c"), PageRequest.of(0, UnpagedResultCap.MAX_ROWS), 3);

        ResponseEntity<List<String>> response = UnpagedResultCap.respond(page);

        assertThat(response.getBody()).containsExactly("a", "b", "c");
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.TOTAL_HEADER)).isEqualTo("3");
        assertThat(response.getHeaders().containsKey(UnpagedResultCap.CAPPED_HEADER)).isFalse();
    }

    @Test
    @DisplayName("a truncated result reports the true total and flags itself")
    void truncatedResultIsFlagged() {
        List<String> content = IntStream.range(0, UnpagedResultCap.MAX_ROWS)
                .mapToObj(Integer::toString)
                .toList();
        Page<String> page = new PageImpl<>(
                content, PageRequest.of(0, UnpagedResultCap.MAX_ROWS), 4321);

        ResponseEntity<List<String>> response = UnpagedResultCap.respond(page);

        assertThat(response.getBody()).hasSize(UnpagedResultCap.MAX_ROWS);
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.TOTAL_HEADER)).isEqualTo("4321");
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.CAPPED_HEADER)).isEqualTo("true");
    }

    @Test
    @DisplayName("a result sitting exactly on the cap is not mistaken for a truncated one")
    void exactlyFullResultIsNotFlagged() {
        List<String> content = IntStream.range(0, UnpagedResultCap.MAX_ROWS)
                .mapToObj(Integer::toString)
                .toList();
        Page<String> page = new PageImpl<>(
                content, PageRequest.of(0, UnpagedResultCap.MAX_ROWS), UnpagedResultCap.MAX_ROWS);

        ResponseEntity<List<String>> response = UnpagedResultCap.respond(page);

        assertThat(response.getHeaders().containsKey(UnpagedResultCap.CAPPED_HEADER)).isFalse();
    }

    @Test
    @DisplayName("an empty result still reports a total")
    void emptyResultReportsZero() {
        Page<String> page = new PageImpl<>(
                List.of(), PageRequest.of(0, UnpagedResultCap.MAX_ROWS), 0);

        ResponseEntity<List<String>> response = UnpagedResultCap.respond(page);

        assertThat(response.getBody()).isEmpty();
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.TOTAL_HEADER)).isEqualTo("0");
        assertThat(response.getHeaders().containsKey(UnpagedResultCap.CAPPED_HEADER)).isFalse();
    }

    @Test
    @DisplayName("the capped read asks for the first page at the cap")
    void firstPageIsSizedAtTheCap() {
        PageRequest request = UnpagedResultCap.firstPage();

        assertThat(request.getPageNumber()).isZero();
        assertThat(request.getPageSize()).isEqualTo(UnpagedResultCap.MAX_ROWS);
    }
}

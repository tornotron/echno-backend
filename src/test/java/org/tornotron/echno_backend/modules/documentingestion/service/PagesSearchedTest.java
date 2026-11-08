package org.tornotron.echno_backend.modules.documentingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PagesSearchedTest {

    @Test
    void runsArePrintedAsRangesAndSinglePagesAsNumbers() {
        assertThat(PagesSearched.describe(PagesSearched.read(1, 40, List.of()))).isEqualTo("1 to 40");
        assertThat(PagesSearched.describe(PagesSearched.read(1, 40, List.of(9, 10)))).isEqualTo("1 to 8, 11 to 40");
        assertThat(PagesSearched.describe(PagesSearched.read(5, 7, List.of(6)))).isEqualTo("5, 7");
        assertThat(PagesSearched.describe(PagesSearched.read(3, 3, List.of()))).isEqualTo("3");
        assertThat(PagesSearched.read(2, 3, List.of(2, 3))).isEmpty();
        assertThat(PagesSearched.describe(List.of())).isEmpty();
    }
}

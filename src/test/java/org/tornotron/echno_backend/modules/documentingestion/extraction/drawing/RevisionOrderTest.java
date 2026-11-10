package org.tornotron.echno_backend.modules.documentingestion.extraction.drawing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RevisionOrderTest {

    @Test
    void revisionsOfOneShapeFollowTheirOwnSequence() {
        assertThat(RevisionOrder.bySequence("A", "B")).hasValueSatisfying(c -> assertThat(c).isNegative());
        assertThat(RevisionOrder.bySequence("Z", "AA")).hasValueSatisfying(c -> assertThat(c).isNegative());
        assertThat(RevisionOrder.bySequence("01", "2")).hasValueSatisfying(c -> assertThat(c).isNegative());
        assertThat(RevisionOrder.bySequence("10", "9")).hasValueSatisfying(c -> assertThat(c).isPositive());
        assertThat(RevisionOrder.bySequence("P1", "P2")).hasValueSatisfying(c -> assertThat(c).isNegative());
        assertThat(RevisionOrder.bySequence("Rev. C", "b")).hasValueSatisfying(c -> assertThat(c).isPositive());
        assertThat(RevisionOrder.bySequence(null, "A")).as("no revision is the oldest")
                .hasValueSatisfying(c -> assertThat(c).isNegative());
    }

    @Test
    void differentShapesFallBackToDatesThenArrival() {
        assertThat(RevisionOrder.bySequence("P3", "C1")).isEmpty();
        assertThat(RevisionOrder.compare("P3", LocalDate.of(2026, 1, 5), 2, "C1", LocalDate.of(2026, 3, 1), 1))
                .isNegative();
        assertThat(RevisionOrder.compare("P3", null, 2, "C1", null, 1)).as("the later arrival is newer").isPositive();
    }

    @Test
    void numbersAndRevisionsAreComparedWithoutCaseOrSpaces() {
        assertThat(RevisionOrder.numberKey(" rcb-ar 101 ")).isEqualTo("RCB-AR101");
        assertThat(RevisionOrder.revisionKey("Rev. B")).isEqualTo("B");
        assertThat(RevisionOrder.revisionKey("REVISION 2")).isEqualTo("2");
        assertThat(RevisionOrder.revisionKey(null)).isEmpty();
    }
}

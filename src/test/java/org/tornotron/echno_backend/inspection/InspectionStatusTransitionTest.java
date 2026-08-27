package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inspection lifecycle graph. Before this existed the update endpoint wrote
 * whatever status the payload carried, so a cancelled inspection could come back
 * as passed and the record would show a conclusion that was never reached.
 *
 * <p>Plain JUnit: the graph is a lookup and needs no Spring context, and every
 * distinct context this suite loads stays cached for the life of a 1 GB JVM.
 */
class InspectionStatusTransitionTest {

    @ParameterizedTest
    @CsvSource({
            // a suggestion is taken up, dismissed, or recorded as already satisfied
            "SUGGESTED,           SCHEDULED",
            "SUGGESTED,           CANCELLED",
            "SUGGESTED,           PASSED",
            // scheduled work is started, cancelled, or simply recorded once carried out
            "SCHEDULED,           IN_PROGRESS",
            "SCHEDULED,           COMPLETED",
            "SCHEDULED,           PASSED_WITH_REMARKS",
            "SCHEDULED,           CANCELLED",
            "IN_PROGRESS,         FAILED",
            // carried out but not yet judged, so only the verdict is left
            "COMPLETED,           PASSED",
            "COMPLETED,           FAILED",
            // re-inspection of a failed inspection, which is forwards and not backwards
            "FAILED,              IN_PROGRESS",
    })
    void allowsTheMovesTheLifecycleIsMadeOf(InspectionStatus from, InspectionStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            // out of a verdict already given: a passed inspection later found defective
            // is a new inspection and an NCR, not a rewrite of the verdict
            "PASSED,              IN_PROGRESS",
            "PASSED,              FAILED",
            "PASSED_WITH_REMARKS, SCHEDULED",
            "CANCELLED,           SCHEDULED",
            "CANCELLED,           PASSED",
            // backwards out of a completed inspection
            "COMPLETED,           SCHEDULED",
            "COMPLETED,           IN_PROGRESS",
            "COMPLETED,           CANCELLED",
            // a failed inspection is re-inspected, not re-judged on the spot
            "FAILED,              PASSED",
            // and nothing goes back to being a suggestion
            "SCHEDULED,           SUGGESTED",
            "PASSED,              SUGGESTED",
    })
    void refusesTheMovesThatWouldRewriteAConclusion(InspectionStatus from, InspectionStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(InspectionStatus.class)
    void alwaysAllowsStayingPut(InspectionStatus status) {
        // the web client sends the whole record back on every save, so an unchanged
        // status is the normal edit and must never be read as an attempted transition
        assertThat(status.canTransitionTo(status)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(InspectionStatus.class)
    void everyStatusHasAnEntryInTheGraph(InspectionStatus status) {
        // allowedNext() would throw on a member the graph forgot, which is how a new
        // enum constant added without a rule gets caught here rather than in production
        assertThat(status.allowedNext()).isNotNull();
    }

    @Test
    void namesWhereTheInspectionEnds() {
        assertThat(InspectionStatus.PASSED.allowedNext()).isEmpty();
        assertThat(InspectionStatus.PASSED_WITH_REMARKS.allowedNext()).isEmpty();
        assertThat(InspectionStatus.CANCELLED.allowedNext()).isEmpty();
    }
}

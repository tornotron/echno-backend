package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The NCR lifecycle graph. It is what stops a non-conformance being closed
 * straight from open, which would make the closure trail a record of nothing.
 *
 * <p>Plain JUnit, for the same reason as {@link InspectionStatusTransitionTest}.
 */
class NcrStatusTransitionTest {

    @ParameterizedTest
    @CsvSource({
            "OPEN,                       ASSIGNED",
            "ASSIGNED,                   CORRECTIVE_ACTION_COMPLETE",
            "CORRECTIVE_ACTION_COMPLETE, VERIFIED",
            "CORRECTIVE_ACTION_COMPLETE, REJECTED",
            "VERIFIED,                   CLOSED",
            "VERIFIED,                   REOPENED",
            "CLOSED,                     REOPENED",
            // rejected work goes back to the assignee, not to the raiser
            "REJECTED,                   ASSIGNED",
            // and a recurrence is reassigned before it can be worked on again
            "REOPENED,                   ASSIGNED",
    })
    void allowsTheMovesTheLifecycleIsMadeOf(NcrStatus from, NcrStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            // closing without an owner, without corrective work and without verification
            // is the whole reason this graph exists
            "OPEN,                       CLOSED",
            "OPEN,                       VERIFIED",
            "OPEN,                       CORRECTIVE_ACTION_COMPLETE",
            "ASSIGNED,                   CLOSED",
            "ASSIGNED,                   VERIFIED",
            "CORRECTIVE_ACTION_COMPLETE, CLOSED",
            // and verification is not something the site engineer can undo
            "VERIFIED,                   CORRECTIVE_ACTION_COMPLETE",
            "VERIFIED,                   REJECTED",
            "CLOSED,                     ASSIGNED",
            "CLOSED,                     VERIFIED",
            // nothing goes back to unassigned: the trail keeps its owner
            "ASSIGNED,                   OPEN",
            "REJECTED,                   OPEN",
    })
    void refusesTheMovesThatWouldSkipOrUndoAStep(NcrStatus from, NcrStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(NcrStatus.class)
    void alwaysAllowsStayingPut(NcrStatus status) {
        assertThat(status.canTransitionTo(status)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(NcrStatus.class)
    void everyStatusHasAnEntryInTheGraph(NcrStatus status) {
        assertThat(status.allowedNext()).isNotNull();
    }

    @Test
    void aReportCanAlwaysComeBack() {
        // no state is terminal: a non-conformance that recurs is recorded on the report
        // that already carries its history, so even a closed one has somewhere to go
        assertThat(NcrStatus.CLOSED.allowedNext()).containsExactly(NcrStatus.REOPENED);
    }

    @Test
    void theTypeFollowsTheInspectionItWasRaisedFrom() {
        assertThat(NcrType.forCategory(InspectionCategory.SAFETY)).isEqualTo(NcrType.SAFETY);
        assertThat(NcrType.forCategory(InspectionCategory.QA_QC)).isEqualTo(NcrType.QUALITY);
        assertThat(NcrType.forCategory(InspectionCategory.COMPLIANCE)).isEqualTo(NcrType.QUALITY);
        assertThat(NcrType.forCategory(InspectionCategory.OTHER)).isEqualTo(NcrType.QUALITY);
        assertThat(NcrType.forCategory(null)).isEqualTo(NcrType.QUALITY);
    }
}

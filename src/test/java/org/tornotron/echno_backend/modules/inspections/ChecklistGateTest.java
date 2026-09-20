package org.tornotron.echno_backend.modules.inspections;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistGate;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistIncompleteException;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistIncompleteException.UnansweredCheckItem;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checklist gate on its own: what stops a submission, what lets one through,
 * and what the refusal says. Plain JUnit; the rules are functions over the payload
 * and need no context.
 */
class ChecklistGateTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Inspection INSPECTION = inspection();

    @Test
    void aFullyAnsweredChecklistGoesThrough() {
        List<InspectionCheckItemRequest> submitted = List.of(
                item("Reinforcement", "Rebar spacing", CheckItemStatus.PASSED, null),
                item("Reinforcement", "Cover blocks", CheckItemStatus.FAILED, "Missing at C4"),
                item("Formwork", "Shutter alignment", CheckItemStatus.NOT_APPLICABLE, null));

        assertThatCode(() -> ChecklistGate.requireAnswered(INSPECTION, submitted, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void anEmptyOrAbsentChecklistIsNotGated() {
        // an AI-generated compliance inspection starts with no check points, and
        // there is nothing unanswered about an empty list
        assertThatCode(() -> ChecklistGate.requireAnswered(INSPECTION, List.of(), List.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChecklistGate.requireAnswered(INSPECTION, null, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void oneUnansweredCheckPointRefusesTheSubmissionAndNamesIt() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        List<InspectionCheckItemRequest> submitted = List.of(
                item("Reinforcement", "Rebar spacing", CheckItemStatus.PASSED, null),
                item("Reinforcement", "Cover blocks", CheckItemStatus.PENDING, null),
                item("Formwork", "Shutter alignment", CheckItemStatus.PASSED, null));

        assertThatThrownBy(() -> ChecklistGate.requireAnswered(INSPECTION, submitted, List.of(first, second, third)))
                .isInstanceOf(ChecklistIncompleteException.class)
                .hasMessageContaining("INSP-2026-0007 cannot be submitted")
                .hasMessageContaining("1 check point is still unanswered")
                .hasMessageContaining("Reinforcement / Cover blocks")
                .satisfies(ex -> {
                    ChecklistIncompleteException refusal = (ChecklistIncompleteException) ex;
                    assertThat(refusal.getInspectionId()).isEqualTo(ID);
                    assertThat(refusal.getInspectionNumber()).isEqualTo("INSP-2026-0007");
                    assertThat(refusal.getItems()).containsExactly(
                            new UnansweredCheckItem(1, second, "Reinforcement", "Cover blocks"));
                });
    }

    @Test
    void theStoredIdsAreOnlyReportedWhenTheyLineUpWithTheSubmittedChecklist() {
        // the client added a check point, so position no longer identifies a stored row
        List<InspectionCheckItemRequest> submitted = List.of(
                item("Reinforcement", "Rebar spacing", CheckItemStatus.PENDING, null),
                item("Reinforcement", "Cover blocks", CheckItemStatus.PENDING, null));

        List<UnansweredCheckItem> unanswered =
                ChecklistGate.unanswered(submitted, List.of(UUID.randomUUID()));

        assertThat(unanswered).extracting(UnansweredCheckItem::id).containsOnlyNulls();
        assertThat(unanswered).extracting(UnansweredCheckItem::index).containsExactly(0, 1);
    }

    @Test
    void theMessageNamesTheFirstFewAndCountsTheRest() {
        InspectionCheckItemRequest[] items = new InspectionCheckItemRequest[8];
        for (int i = 0; i < items.length; i++) {
            items[i] = item("Finishing", "Point " + (i + 1), CheckItemStatus.PENDING, null);
        }

        assertThatThrownBy(() -> ChecklistGate.requireAnswered(INSPECTION, List.of(items), List.of()))
                .hasMessageContaining("8 check points are still unanswered")
                .hasMessageContaining("Finishing / Point 5")
                .hasMessageContaining("and 3 more")
                .hasMessageNotContaining("Point 6");
    }

    @Test
    void aNotDoneCheckPointWithARemarkIsAnAnswer() {
        List<InspectionCheckItemRequest> submitted = List.of(
                item("Reinforcement", "Rebar spacing", CheckItemStatus.PASSED, null),
                item("Services", "Conduit pressure test",
                        CheckItemStatus.NOT_DONE, "Pump not on site; test deferred to next visit"));

        assertThatCode(() -> ChecklistGate.requireRemarksOnNotDone(submitted))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChecklistGate.requireAnswered(INSPECTION, submitted, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void aNotDoneCheckPointWithoutARemarkIsRefused() {
        List<InspectionCheckItemRequest> blank = List.of(
                item("Reinforcement", "Rebar spacing", CheckItemStatus.PASSED, null),
                item("Services", "Conduit pressure test", CheckItemStatus.NOT_DONE, "   "));
        List<InspectionCheckItemRequest> absent = List.of(
                item("Services", "Conduit pressure test", CheckItemStatus.NOT_DONE, null));

        assertThatThrownBy(() -> ChecklistGate.requireRemarksOnNotDone(blank))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("\"Conduit pressure test\" under \"Services\"")
                .hasMessageContaining("needs a remark");
        assertThatThrownBy(() -> ChecklistGate.requireRemarksOnNotDone(absent))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> ChecklistGate.requireRemarkWhenNotDone(
                CheckItemStatus.NOT_DONE, null, "Services", "Conduit pressure test"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theRemarkRuleLeavesEveryOtherOutcomeAlone() {
        for (CheckItemStatus status : CheckItemStatus.values()) {
            if (status == CheckItemStatus.NOT_DONE) {
                continue;
            }
            assertThatCode(() -> ChecklistGate.requireRemarksOnNotDone(
                    List.of(item("Cat", "Point", status, null))))
                    .as(status.name())
                    .doesNotThrowAnyException();
        }
        assertThatCode(() -> ChecklistGate.requireRemarksOnNotDone(null)).doesNotThrowAnyException();
    }

    @Test
    void onlyPendingIsUnanswered() {
        assertThat(CheckItemStatus.PENDING.isAnswered()).isFalse();
        for (CheckItemStatus status : CheckItemStatus.values()) {
            if (status != CheckItemStatus.PENDING) {
                assertThat(status.isAnswered()).as(status.name()).isTrue();
            }
        }
    }

    private static Inspection inspection() {
        Inspection inspection = new Inspection();
        inspection.setId(ID);
        inspection.setInspectionNumber("INSP-2026-0007");
        return inspection;
    }

    private static InspectionCheckItemRequest item(String category, String checkPoint,
                                                   CheckItemStatus status, String remarks) {
        return new InspectionCheckItemRequest(category, checkPoint, null, status, remarks,
                false, null, null, null, null, null, null, "medium", null);
    }
}

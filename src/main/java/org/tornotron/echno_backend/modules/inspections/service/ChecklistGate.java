package org.tornotron.echno_backend.modules.inspections.service;

import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.inspections.CheckItemStatus;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemRequest;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistIncompleteException.UnansweredCheckItem;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The two rules that make a checklist a record rather than a form left half
 * filled in.
 *
 * <p>The first is the gate: an inspection is not submitted for a verdict while a
 * check point is still {@link CheckItemStatus#PENDING}. The inspector answers
 * every point, or marks the ones that could not be carried out as
 * {@link CheckItemStatus#NOT_DONE} with a remark, and the submission then goes
 * through with those remarks on the record for the approver to weigh. An
 * inspection with no checklist at all is not gated; an AI-generated compliance
 * inspection starts that way, and there is nothing unanswered about an empty
 * list.
 *
 * <p>The second is what makes the exception honest: a not-done item is only an
 * answer because of its remark, so one without a remark is refused at write time
 * whatever state the inspection is in. Otherwise "not done" would be a way to
 * empty the checklist past the gate.
 *
 * <p>Both rules read the payload, and run before the stored inspection is touched:
 * a refusal then leaves nothing half-applied on the managed entity, which matters
 * wherever the service call shares a transaction with its caller. Pure functions,
 * so the rules are testable without a context and cannot drift between the update
 * path and any other path that writes a check item.
 */
public final class ChecklistGate {

    private ChecklistGate() {
    }

    /**
     * Refuses the submission while any check point in the payload is unanswered.
     *
     * @param inspection The stored inspection being submitted, for the message.
     * @param submitted  The checklist as the payload carries it. Null or empty is an
     *                   inspection with no checklist, which is not gated.
     * @param storedIds  The ids of the stored check items, in order. Reported back by
     *                   position when the payload carries the checklist at the same
     *                   length, since an update replaces the rows and the payload has no
     *                   ids of its own.
     * @throws ChecklistIncompleteException listing every pending check point.
     */
    public static void requireAnswered(Inspection inspection,
                                       List<InspectionCheckItemRequest> submitted,
                                       List<UUID> storedIds) {
        List<UnansweredCheckItem> unanswered = unanswered(submitted, storedIds);
        if (!unanswered.isEmpty()) {
            throw new ChecklistIncompleteException(
                    inspection.getId(), inspection.getInspectionNumber(), unanswered);
        }
    }

    /** The pending check points, in order, with the stored id at each position when known. */
    public static List<UnansweredCheckItem> unanswered(List<InspectionCheckItemRequest> submitted,
                                                       List<UUID> storedIds) {
        List<UnansweredCheckItem> unanswered = new ArrayList<>();
        if (submitted == null) {
            return unanswered;
        }
        boolean idsLineUp = storedIds != null && storedIds.size() == submitted.size();
        for (int i = 0; i < submitted.size(); i++) {
            InspectionCheckItemRequest item = submitted.get(i);
            if (item.status() == null || !item.status().isAnswered()) {
                unanswered.add(new UnansweredCheckItem(
                        i, idsLineUp ? storedIds.get(i) : null,
                        item.category(), item.checkPoint()));
            }
        }
        return unanswered;
    }

    /**
     * Refuses any not-done check point in the payload that carries no remark.
     *
     * @param submitted The checklist as the payload carries it; null passes.
     * @throws InvalidRequestException naming the first offending check point.
     */
    public static void requireRemarksOnNotDone(List<InspectionCheckItemRequest> submitted) {
        if (submitted == null) {
            return;
        }
        for (InspectionCheckItemRequest item : submitted) {
            requireRemarkWhenNotDone(item.status(), item.remarks(), item.category(), item.checkPoint());
        }
    }

    /**
     * Refuses a not-done check point that carries no remark.
     *
     * @param status     The status being written.
     * @param remarks    The remark being written alongside it.
     * @param category   For the message.
     * @param checkPoint For the message.
     * @throws InvalidRequestException when the status is not-done and the remark is blank.
     */
    public static void requireRemarkWhenNotDone(CheckItemStatus status, String remarks,
                                                String category, String checkPoint) {
        if (status == CheckItemStatus.NOT_DONE && (remarks == null || remarks.isBlank())) {
            throw new InvalidRequestException(
                    "Check point \"" + checkPoint + "\" under \"" + category
                            + "\" is marked not done and needs a remark saying why.");
        }
    }
}

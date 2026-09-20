package org.tornotron.echno_backend.modules.inspections.service;

import java.util.List;
import java.util.UUID;

/**
 * A submission was refused because check points on the checklist are still
 * unanswered. Carries the items so the response can point at them rather than
 * leaving the client to work out which of a hundred rows it was.
 *
 * <p>Mapped to 422 by {@code InspectionsExceptionHandler}: the payload is well
 * formed, and the move it asks for is part of the lifecycle; it is the record that
 * is not ready.
 */
public class ChecklistIncompleteException extends RuntimeException {

    /** How many items the message spells out before it says "and N more". */
    private static final int NAMED_IN_MESSAGE = 5;

    private final UUID inspectionId;
    private final String inspectionNumber;
    private final List<UnansweredCheckItem> items;

    public ChecklistIncompleteException(UUID inspectionId, String inspectionNumber,
                                        List<UnansweredCheckItem> items) {
        super(describe(inspectionNumber, items));
        this.inspectionId = inspectionId;
        this.inspectionNumber = inspectionNumber;
        this.items = List.copyOf(items);
    }

    public UUID getInspectionId() {
        return inspectionId;
    }

    public String getInspectionNumber() {
        return inspectionNumber;
    }

    /** The unanswered check points, in checklist order. Never empty. */
    public List<UnansweredCheckItem> getItems() {
        return items;
    }

    private static String describe(String inspectionNumber, List<UnansweredCheckItem> items) {
        StringBuilder sb = new StringBuilder("Inspection ")
                .append(inspectionNumber == null ? "" : inspectionNumber)
                .append(" cannot be submitted: ")
                .append(items.size())
                .append(items.size() == 1 ? " check point is" : " check points are")
                .append(" still unanswered. Record an outcome for each, or mark it not done with a remark: ");
        int named = Math.min(items.size(), NAMED_IN_MESSAGE);
        for (int i = 0; i < named; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(items.get(i).label());
        }
        if (items.size() > named) {
            sb.append("; and ").append(items.size() - named).append(" more");
        }
        return sb.append('.').toString();
    }

    /**
     * One unanswered check point.
     *
     * @param index      Its position in the submitted checklist, from zero. Always
     *                   present: an update replaces the check items wholesale, so
     *                   position is the one identity the payload and the store share.
     * @param id         The stored id of the check point at that position before the
     *                   refused update, when the payload carried the checklist back at
     *                   the same length; null otherwise. The ids of the rows the refused
     *                   update would have written are never exposed, since the refusal
     *                   rolls them back.
     * @param category   The grouping the check point sits under.
     * @param checkPoint What was being checked.
     */
    public record UnansweredCheckItem(int index, UUID id, String category, String checkPoint) {

        /** "Category / check point", for a message. */
        public String label() {
            String cat = category == null || category.isBlank() ? "Uncategorised" : category;
            String point = checkPoint == null || checkPoint.isBlank() ? "check point " + (index + 1) : checkPoint;
            return cat + " / " + point;
        }
    }
}

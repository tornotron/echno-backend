package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle state of an inspection. The wire value is the hyphenated form from
 * {@link #getValue()}; the constant name is the database representation stored
 * by {@code @Enumerated(STRING)}.
 *
 * <p>{@link #canTransitionTo} states which moves are real. The update endpoint
 * used to take whatever status the payload carried, so an inspection could go
 * from cancelled back to passed, or from passed to scheduled, and the record
 * would show a conclusion that was never reached. The graph is deliberately
 * permissive about how an inspection is concluded, because it is: work is often
 * carried out and recorded afterwards, so scheduled straight to passed is a
 * normal day and not an error. What it refuses is going backwards out of a
 * conclusion, with one exception that is not backwards at all: a failed
 * inspection returning to in-progress is the re-inspection of the failed items.
 */
public enum InspectionStatus {
    SCHEDULED("scheduled"),
    IN_PROGRESS("in-progress"),
    COMPLETED("completed"),
    FAILED("failed"),
    PASSED("passed"),
    PASSED_WITH_REMARKS("passed-with-remarks"),
    CANCELLED("cancelled"),
    SUGGESTED("suggested");

    private final String value;

    InspectionStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static InspectionStatus fromValue(String value) {
        for (InspectionStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown inspection status: " + value);
    }

    private static final Map<InspectionStatus, Set<InspectionStatus>> NEXT;

    static {
        Set<InspectionStatus> conclusions =
                EnumSet.of(COMPLETED, PASSED, PASSED_WITH_REMARKS, FAILED);
        Map<InspectionStatus, Set<InspectionStatus>> next = new EnumMap<>(InspectionStatus.class);
        // An AI-generated compliance suggestion is taken up, dismissed, or recorded as
        // already satisfied. The last one is why the conclusions are here: a client who
        // already holds the fire NOC the model suggested marks it obtained, and making
        // them schedule an inspection they will never carry out to say so is theatre.
        next.put(SUGGESTED, plus(conclusions, SCHEDULED, IN_PROGRESS, CANCELLED));
        // Scheduled work may be started, cancelled, or simply recorded once done.
        next.put(SCHEDULED, plus(conclusions, IN_PROGRESS, CANCELLED));
        next.put(IN_PROGRESS, plus(conclusions, CANCELLED));
        // Carried out but not yet judged: only the verdict is left.
        next.put(COMPLETED, EnumSet.of(PASSED, PASSED_WITH_REMARKS, FAILED));
        // A failed inspection is re-inspected. That is forwards, not backwards.
        next.put(FAILED, EnumSet.of(IN_PROGRESS));
        // Terminal. Passed work that turns out to be defective is a new inspection
        // and an NCR against this one, not a rewrite of the verdict already given.
        next.put(PASSED, EnumSet.noneOf(InspectionStatus.class));
        next.put(PASSED_WITH_REMARKS, EnumSet.noneOf(InspectionStatus.class));
        next.put(CANCELLED, EnumSet.noneOf(InspectionStatus.class));
        NEXT = Collections.unmodifiableMap(next);
    }

    private static Set<InspectionStatus> plus(Set<InspectionStatus> base,
                                              InspectionStatus... extra) {
        Set<InspectionStatus> combined = EnumSet.copyOf(base);
        Collections.addAll(combined, extra);
        return combined;
    }

    /**
     * Whether an inspection in this state may move to {@code target}. Staying put
     * is always allowed: the web client sends the whole record back on every save,
     * so an unchanged status is the normal case, not an attempted transition.
     *
     * @param target The state being moved to.
     * @return true when the move is part of the lifecycle, or is no move at all.
     */
    public boolean canTransitionTo(InspectionStatus target) {
        return this == target || NEXT.get(this).contains(target);
    }

    /** The states this one may move to, for an error message that says what is allowed. */
    public Set<InspectionStatus> allowedNext() {
        return Collections.unmodifiableSet(NEXT.get(this));
    }
}

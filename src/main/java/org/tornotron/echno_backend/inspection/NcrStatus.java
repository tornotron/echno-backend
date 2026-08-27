package org.tornotron.echno_backend.inspection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Where a non-conformance report has reached in its lifecycle.
 *
 * <p>The path an NCR normally walks is
 * {@code OPEN -> ASSIGNED -> CORRECTIVE_ACTION_COMPLETE -> VERIFIED -> CLOSED},
 * with two ways out of the straight line. {@link #REJECTED} is where a verifier
 * puts work that was declared complete but is not acceptable, and it goes back to
 * the site engineer. {@link #REOPENED} is where an NCR goes when the same
 * non-conformance is found again after it was verified or closed, which is a real
 * outcome on site and must not be recorded by raising a second NCR: the history of
 * the first one is the evidence that it recurred.
 *
 * <p>{@link #canTransitionTo} is the whole of that rule and the only place it is
 * written down. Staying put is always allowed, because the web client sends a
 * record back unchanged on every save and an unchanged status must not be an error.
 *
 * <p>The wire value is the hyphenated lowercase form from {@link #getValue()}; the
 * constant name is the database representation stored by
 * {@code @Enumerated(STRING)}.
 */
public enum NcrStatus {
    OPEN("open"),
    ASSIGNED("assigned"),
    CORRECTIVE_ACTION_COMPLETE("corrective-action-complete"),
    VERIFIED("verified"),
    CLOSED("closed"),
    REJECTED("rejected"),
    REOPENED("reopened");

    private final String value;

    NcrStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static NcrStatus fromValue(String value) {
        for (NcrStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown NCR status: " + value);
    }

    private static final Map<NcrStatus, Set<NcrStatus>> NEXT;

    static {
        Map<NcrStatus, Set<NcrStatus>> next = new EnumMap<>(NcrStatus.class);
        // Raised but not yet given to anyone.
        next.put(OPEN, EnumSet.of(ASSIGNED));
        // With a site engineer, who reports the corrective work done.
        next.put(ASSIGNED, EnumSet.of(CORRECTIVE_ACTION_COMPLETE));
        // Declared done, awaiting re-inspection: accepted, or sent back.
        next.put(CORRECTIVE_ACTION_COMPLETE, EnumSet.of(VERIFIED, REJECTED));
        // Re-inspected and accepted. Closure is a separate, role-gated act.
        next.put(VERIFIED, EnumSet.of(CLOSED, REOPENED));
        // Closed, and reopened only if the same non-conformance comes back.
        next.put(CLOSED, EnumSet.of(REOPENED));
        // Sent back, so it returns to the assignee rather than to the raiser.
        next.put(REJECTED, EnumSet.of(ASSIGNED));
        // Reopened work is reassigned before it can be worked on again.
        next.put(REOPENED, EnumSet.of(ASSIGNED));
        NEXT = Collections.unmodifiableMap(next);
    }

    /**
     * Whether an NCR in this state may move to {@code target}.
     *
     * @param target The state being moved to.
     * @return true when the move is part of the lifecycle, or is no move at all.
     */
    public boolean canTransitionTo(NcrStatus target) {
        return this == target || NEXT.get(this).contains(target);
    }

    /** The states this one may move to, for an error message that says what is allowed. */
    public Set<NcrStatus> allowedNext() {
        return Collections.unmodifiableSet(NEXT.get(this));
    }
}

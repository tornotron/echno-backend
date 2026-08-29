package org.tornotron.echno_backend.compliance.job;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where one compliance generation job has got to.
 *
 * <p>The set is chosen so a caller can tell apart the five outcomes it has to show
 * differently, and no fewer:
 *
 * <ul>
 *   <li>{@link #QUEUED} accepted and waiting for a worker. The user sees "queued".</li>
 *   <li>{@link #RUNNING} a worker has it and is working through the batches. This is the
 *       state the progress counters move in.</li>
 *   <li>{@link #SUCCEEDED} finished, every candidate rule was assessed, and at least one
 *       compliance inspection was created.</li>
 *   <li>{@link #NOTHING_TO_REPORT} finished, every candidate rule was assessed, and
 *       nothing was created. That is a real answer, not a non-answer: either the model
 *       found no rule applicable to this project, or every applicable compliance already
 *       existed from an earlier run.</li>
 *   <li>{@link #FAILED} the run did not cover every rule, so it has no result to show.
 *       Nothing was created.</li>
 * </ul>
 *
 * <p>Splitting {@link #SUCCEEDED} from {@link #NOTHING_TO_REPORT} rather than leaving the
 * caller to read a zero count is the point of the whole set. Before the truncation checks
 * landed, "the model was cut off by its token limit" and "nothing applied" both reached
 * the user as an empty list, and the first one is a failure that has to be fixed while the
 * second needs no action at all. A job that recorded both as one success state would have
 * put that ambiguity straight back, one layer further from anyone who could notice it.
 *
 * <p>Database representation is the constant name; the JSON wire form is the hyphenated
 * lowercase value, as elsewhere in the codebase.
 */
public enum ComplianceJobStatus {

    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    NOTHING_TO_REPORT("nothing-to-report"),
    FAILED("failed");

    private final String value;

    ComplianceJobStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ComplianceJobStatus fromValue(String value) {
        for (ComplianceJobStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown compliance job status: " + value);
    }

    /** Whether the job is finished and its row will not change again. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == NOTHING_TO_REPORT || this == FAILED;
    }

    /** Whether the job still owns the project, so a second request joins it instead of starting another. */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}

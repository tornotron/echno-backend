package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Per-check-point outcome within an inspection. The wire value is the hyphenated
 * form from {@link #getValue()}; the constant name is the database representation.
 * The passed and failed counts on an inspection are derived from these statuses.
 *
 * <p>{@link #PENDING} is the one unanswered state. Everything else is an answer,
 * including {@link #NOT_DONE}, which records that the check could not be carried
 * out and must carry a remark saying why; the checklist gate in
 * {@code InspectionService} refuses a submission while any item is still pending,
 * and a not-done item without its remark is refused at write time.
 */
public enum CheckItemStatus {
    PASSED("passed"),
    FAILED("failed"),
    NOT_APPLICABLE("not-applicable"),
    PENDING("pending"),
    NOT_DONE("not-done");

    private final String value;

    CheckItemStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Whether this status is an answer. Only {@link #PENDING} is not; a submission
     * is refused while any check point still holds it.
     */
    public boolean isAnswered() {
        return this != PENDING;
    }

    @JsonCreator
    public static CheckItemStatus fromValue(String value) {
        for (CheckItemStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown check item status: " + value);
    }
}

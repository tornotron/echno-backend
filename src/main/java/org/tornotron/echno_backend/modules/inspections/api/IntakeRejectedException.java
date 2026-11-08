package org.tornotron.echno_backend.modules.inspections.api;

import org.tornotron.echno_backend.common.exception.InvalidRequestException;

/**
 * Refuses one machine finding with a stable code the producer can act on. A 400 on the single
 * intake endpoint, and that item's {@code error} in a batch.
 */
public class IntakeRejectedException extends InvalidRequestException {

    private final String code;
    private final String field;

    /**
     * @param code    the stable code, for example {@code UNKNOWN_CLASS}
     * @param field   the request field at fault, or null
     * @param message the sentence for a person
     */
    public IntakeRejectedException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String getCode() {
        return code;
    }

    public String getField() {
        return field;
    }
}

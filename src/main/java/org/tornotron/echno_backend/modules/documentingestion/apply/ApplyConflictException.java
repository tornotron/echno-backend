package org.tornotron.echno_backend.modules.documentingestion.apply;

import org.tornotron.echno_backend.common.exception.InvalidRequestException;

/**
 * A change refused because its target was edited by hand after a value was applied to it (design section
 * 4.8). It carries the value on the record and the value last applied as well as the sentence, so the review
 * screen can show all three values without reading them out of the text.
 */
public class ApplyConflictException extends InvalidRequestException {

    private final String recordValue;
    private final String appliedValue;

    public ApplyConflictException(String message, String recordValue, String appliedValue) {
        super(message);
        this.recordValue = recordValue;
        this.appliedValue = appliedValue;
    }

    /** The value the live record holds now. */
    public String recordValue() {
        return recordValue;
    }

    /** The value last applied to the record from a document. */
    public String appliedValue() {
        return appliedValue;
    }
}

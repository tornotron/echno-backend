package org.tornotron.echno_backend.modules.inspections.observation;

import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;

/** A 404 on a machine finding that also carries a stable code, for a batch item's error. */
public class IntakeReferenceNotFoundException extends ResourceNotFoundException {

    private final String code;

    public IntakeReferenceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

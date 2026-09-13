package org.tornotron.echno_backend.modules.inspections.dataset;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An export was asked for on an organization that has not recorded dataset consent (#790). */
@ResponseStatus(HttpStatus.CONFLICT)
public class DatasetConsentMissingException extends RuntimeException {

    public DatasetConsentMissingException(Long organizationId) {
        super("Organization " + organizationId + " has not recorded dataset consent; nothing is exported");
    }
}

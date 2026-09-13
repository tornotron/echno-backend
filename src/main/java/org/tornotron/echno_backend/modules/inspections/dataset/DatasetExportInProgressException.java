package org.tornotron.echno_backend.modules.inspections.dataset;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/** An export was asked for while a run of the same organization is still running (#812). */
@ResponseStatus(HttpStatus.CONFLICT)
public class DatasetExportInProgressException extends RuntimeException {

    public DatasetExportInProgressException(Long organizationId, UUID runId) {
        super("Organization " + organizationId + " already has dataset export run " + runId
                + " running; read its status and start another once it has finished");
    }
}

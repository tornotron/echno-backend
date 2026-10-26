package org.tornotron.echno_backend.modules.inspections.observation;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An image that observation regions are drawn on; deleting it would pull the evidence from under their decisions. */
@ResponseStatus(HttpStatus.CONFLICT)
public class EvidenceInUseByRegionException extends RuntimeException {

    public EvidenceInUseByRegionException(Long attachmentId, List<UUID> observationIds) {
        super("Attachment " + attachmentId + " has regions drawn on it by observations " + observationIds
                + "; it cannot be deleted");
    }
}

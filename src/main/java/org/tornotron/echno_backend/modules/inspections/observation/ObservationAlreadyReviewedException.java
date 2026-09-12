package org.tornotron.echno_backend.modules.inspections.observation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/** A second decision on an observation that already has one; the first decision stands. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ObservationAlreadyReviewedException extends RuntimeException {

    public ObservationAlreadyReviewedException(UUID id) {
        super("Observation " + id + " has already been reviewed");
    }
}

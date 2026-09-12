package org.tornotron.echno_backend.modules.inspections;

import org.tornotron.echno_backend.common.dto.AttachmentOwner;

import java.util.UUID;

/** The attachment owner for images and clips filed against an observation. */
public final class ObservationEvidence {

    public static final String ENTITY_TYPE = "OBSERVATION_EVIDENCE";

    private ObservationEvidence() {
    }

    public static AttachmentOwner ownerOf(UUID observationId) {
        return AttachmentOwner.of(ENTITY_TYPE, observationId);
    }
}

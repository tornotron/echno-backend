package org.tornotron.echno_backend.modules.inspections;

import org.tornotron.echno_backend.common.dto.AttachmentOwner;

import java.util.UUID;

/** The attachment owner for images and clips filed against an observation. */
public final class ObservationEvidence {

    public static final String ENTITY_TYPE = "OBSERVATION_EVIDENCE";

    /** An image a producer uploaded for a finding it has not posted yet; filed against the upload's id. */
    public static final String INTAKE_UPLOAD_ENTITY_TYPE = "OBSERVATION_INTAKE_UPLOAD";

    private ObservationEvidence() {
    }

    public static AttachmentOwner ownerOf(UUID observationId) {
        return AttachmentOwner.of(ENTITY_TYPE, observationId);
    }

    public static AttachmentOwner intakeUploadOwnerOf(UUID uploadId) {
        return AttachmentOwner.of(INTAKE_UPLOAD_ENTITY_TYPE, uploadId);
    }
}

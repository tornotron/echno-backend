package org.tornotron.echno_backend.modules.workprogress.domain;

import java.util.UUID;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;

/**
 * How a progress inspection's evidence (site photos, measurement sheets) is filed against the
 * shared attachment store: one owner type keyed by the inspection's UUID, so the platform's
 * presign, upload and register path works unchanged. Files land under {@code progress/}.
 */
public final class ProgressInspectionEvidence {

    public static final String ENTITY_TYPE = "PROGRESS_INSPECTION_EVIDENCE";

    private ProgressInspectionEvidence() {
    }

    public static AttachmentOwner ownerOf(UUID inspectionId) {
        return AttachmentOwner.of(ENTITY_TYPE, inspectionId);
    }

    public static String folderFor(UUID inspectionId) {
        return ownerOf(inspectionId).folder() + "/" + inspectionId;
    }
}

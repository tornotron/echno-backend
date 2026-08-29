package org.tornotron.echno_backend.inspection;

import org.tornotron.echno_backend.common.dto.AttachmentOwner;

import java.util.UUID;

/**
 * How an inspection's evidence is filed in the shared attachment table.
 *
 * <p>Mirrors {@code AssetDocuments}. The type is the only thing the storage layer needs: the
 * folder is derived from the part before the first underscore, so evidence lands under
 * {@code inspection/} with nothing else to configure.
 */
public final class InspectionEvidence {

    /**
     * The attachment entity type evidence is filed under.
     *
     * <p>"Evidence" rather than "attachments" because that is what these files are for. A
     * compliance inspection standing in for a permit, a certificate or a statutory approval is
     * not a usable audit record without the document itself, and the file is the thing the record
     * rests on rather than an incidental extra.
     */
    public static final String ENTITY_TYPE = "INSPECTION_EVIDENCE";

    private InspectionEvidence() {
    }

    /** The owner to file an inspection's evidence against. */
    public static AttachmentOwner ownerOf(UUID inspectionId) {
        return AttachmentOwner.of(ENTITY_TYPE, inspectionId);
    }
}

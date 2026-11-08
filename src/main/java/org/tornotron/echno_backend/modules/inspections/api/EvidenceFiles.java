package org.tornotron.echno_backend.modules.inspections.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the evidence files of the current tenant for another module, without that module reaching
 * into the attachment owner types of inspections. Every method needs the tenant pinned.
 */
public interface EvidenceFiles {

    /**
     * Every file filed as evidence on one inspection, images or not, oldest first.
     *
     * @throws org.tornotron.echno_backend.common.exception.ResourceNotFoundException if the inspection
     *         is not in this tenant
     */
    List<EvidenceFile> ofInspection(UUID inspectionId);

    /**
     * Every file filed as evidence on one observation, images or not, oldest first.
     *
     * @throws org.tornotron.echno_backend.common.exception.ResourceNotFoundException if the observation
     *         is not in this tenant
     */
    List<EvidenceFile> ofObservation(UUID observationId);

    /**
     * One attachment, when it is inspection or observation evidence of this tenant.
     * Empty for anything else, including files of other tenants and other owner types.
     */
    Optional<EvidenceFile> find(Long attachmentId);
}

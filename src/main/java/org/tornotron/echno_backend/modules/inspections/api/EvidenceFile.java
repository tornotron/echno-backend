package org.tornotron.echno_backend.modules.inspections.api;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A file filed as inspection or observation evidence, as another module needs to know it: which
 * project and record it belongs to and what the stored object is. Exactly one of
 * {@code inspectionId} and {@code observationId} is set.
 *
 * @param organizationId the tenant
 * @param projectId      the project of the inspection or observation, or null when it has none
 * @param inspectionId   the inspection the image is filed against, for inspection evidence
 * @param observationId  the observation the image is filed against, for observation evidence
 * @param attachmentId   the attachment row
 * @param storageKey     the object key in the attachments bucket
 * @param contentType    the stored content type
 * @param uploadedAt     when the attachment row was created
 */
public record EvidenceFile(
        Long organizationId,
        Long projectId,
        UUID inspectionId,
        UUID observationId,
        Long attachmentId,
        String storageKey,
        String contentType,
        LocalDateTime uploadedAt
) {}

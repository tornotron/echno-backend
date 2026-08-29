package org.tornotron.echno_backend.common.dto;

import java.util.UUID;

/**
 * The record a file is filed against: its attachment type, and its key.
 *
 * <p>Most of the application keys its rows with a {@code Long}, and the attachment table was
 * built for exactly that. The inspection module keys everything with a {@code UUID}, and a UUID
 * does not fit a BIGINT, so an inspection had nowhere to put a file. Rather than duplicating the
 * upload, presign, register and list paths once per key type, each takes one of these and the
 * key type stops being visible past the boundary.
 *
 * <p>Exactly one of the two keys is set, which is the same rule the database enforces with the
 * {@code ck_attachment_entity_key} check constraint. The two factory methods are the only way to
 * build one, so the rule holds by construction.
 *
 * @param entityType The attachment entity type, for example ISSUE_ATTACHMENTS or INSPECTION_EVIDENCE
 * @param entityId   The record's numeric id, or null when it is keyed by UUID
 * @param entityUuid The record's UUID, or null when it is keyed by a numeric id
 */
public record AttachmentOwner(String entityType, Long entityId, UUID entityUuid) {

    public AttachmentOwner {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("An attachment must state the type of record it belongs to");
        }
        if ((entityId == null) == (entityUuid == null)) {
            throw new IllegalArgumentException(
                    "An attachment must be filed against exactly one of a numeric id and a UUID");
        }
    }

    /** A record keyed by a numeric id. */
    public static AttachmentOwner of(String entityType, Long entityId) {
        return new AttachmentOwner(entityType, entityId, null);
    }

    /** A record keyed by a UUID. */
    public static AttachmentOwner of(String entityType, UUID entityUuid) {
        return new AttachmentOwner(entityType, null, entityUuid);
    }

    /**
     * The storage folder files for this record go in: the part of the entity type before the
     * first underscore, lower-cased. {@code INSPECTION_EVIDENCE} therefore lands under
     * {@code inspection/} with nothing in the storage layer needing to know it exists.
     *
     * @return The folder name
     */
    public String folder() {
        return entityType.split("_", 2)[0].toLowerCase();
    }

    /** The key as it reads in a message to the caller. */
    public String keyAsText() {
        return entityId != null ? entityId.toString() : entityUuid.toString();
    }
}

package org.tornotron.echno_backend.modules.sitenotes.domain;

import java.util.UUID;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;

/**
 * How a note's photo is filed against the shared attachment store: one owner type, keyed by
 * the note's UUID, so the platform's presign-then-register path works unchanged.
 */
public final class SiteNotePhotos {

    public static final String ENTITY_TYPE = "SITE_NOTE_PHOTO";

    private SiteNotePhotos() {
    }

    public static AttachmentOwner ownerOf(UUID noteId) {
        return AttachmentOwner.of(ENTITY_TYPE, noteId);
    }

    public static String folderFor(UUID noteId) {
        return ownerOf(noteId).folder() + "/" + noteId;
    }
}

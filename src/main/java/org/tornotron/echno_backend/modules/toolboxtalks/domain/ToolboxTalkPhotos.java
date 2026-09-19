package org.tornotron.echno_backend.modules.toolboxtalks.domain;

import java.util.UUID;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;

/**
 * How a talk's photo evidence is filed against the shared attachment store: one owner type,
 * keyed by the talk's UUID, so the platform's presign-then-register path works unchanged.
 */
public final class ToolboxTalkPhotos {

    public static final String ENTITY_TYPE = "TOOLBOX_TALK_PHOTO";

    private ToolboxTalkPhotos() {
    }

    public static AttachmentOwner ownerOf(UUID talkId) {
        return AttachmentOwner.of(ENTITY_TYPE, talkId);
    }

    public static String folderFor(UUID talkId) {
        return ownerOf(talkId).folder() + "/" + talkId;
    }
}

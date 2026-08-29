package org.tornotron.echno_backend.common.dto;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule that keeps the attachment table's two keys from disagreeing with each other.
 *
 * <p>The database says a row must name exactly one of a numeric id and a UUID, with the
 * {@code ck_attachment_entity_key} check constraint. This is the same rule stated where the value
 * is built, so a path that gets it wrong fails at the call rather than at the insert.
 */
class AttachmentOwnerTest {

    @Test
    void aRecordIsKeyedByItsNumericIdOrItsUuid_neverBoth() {
        assertThatThrownBy(() -> new AttachmentOwner("ISSUE_ATTACHMENTS", 4L, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void aRecordThatNamesNeitherKeyIsRefused() {
        assertThatThrownBy(() -> new AttachmentOwner("ISSUE_ATTACHMENTS", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void aRecordMustSayWhatKindOfThingItIs() {
        assertThatThrownBy(() -> AttachmentOwner.of("  ", 4L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStorageFolderIsTheTypeBeforeTheFirstUnderscore() {
        UUID inspectionId = UUID.randomUUID();

        assertThat(AttachmentOwner.of("INSPECTION_EVIDENCE", inspectionId).folder()).isEqualTo("inspection");
        assertThat(AttachmentOwner.of("ISSUE_ATTACHMENTS", 4L).folder()).isEqualTo("issue");
        assertThat(AttachmentOwner.of("ORGANIZATION", 4L).folder()).isEqualTo("organization");
    }

    @Test
    void eitherKindOfKeyReadsBackInAMessage() {
        UUID inspectionId = UUID.randomUUID();

        assertThat(AttachmentOwner.of("INSPECTION_EVIDENCE", inspectionId).keyAsText())
                .isEqualTo(inspectionId.toString());
        assertThat(AttachmentOwner.of("ISSUE_ATTACHMENTS", 4L).keyAsText()).isEqualTo("4");
    }
}

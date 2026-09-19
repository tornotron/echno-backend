package org.tornotron.echno_backend.modules.toolboxtalks.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when a toolbox talk is recorded. This is the module's public surface: a listener
 * anywhere (the project activity feed, a notification) reacts to it without a compile-time
 * dependency on the module's internals.
 *
 * <p>Published inside the recording transaction, so a listener that must see the committed
 * row uses {@code @TransactionalEventListener(phase = AFTER_COMMIT)}; the platform's event
 * bus delivers it after the commit and drops it on rollback.
 */
public record ToolboxTalkRecordedEvent(
        Long organizationId,
        UUID talkId,
        Long projectId,
        LocalDate talkDate,
        Long conductorEmployeeId,
        int attendeeCount) {
}

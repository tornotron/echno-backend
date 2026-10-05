package org.tornotron.echno_backend.modules.sitenotes.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when a site note is added. This is part of the module's public surface: a listener
 * anywhere (a project activity feed, a notification) reacts to it without a compile-time
 * dependency on the module's internals.
 *
 * <p>Published inside the writing transaction, so a listener that must see the committed row
 * uses {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. It carries ids and a date,
 * never an entity.
 */
public record SiteNoteAddedEvent(
        Long organizationId,
        UUID noteId,
        Long projectId,
        LocalDate noteDate,
        Long authorEmployeeId) {
}

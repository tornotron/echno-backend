package org.tornotron.echno_backend.leave;

import org.tornotron.echno_backend.leave.enums.NotificationType;

/**
 * One notification, composed but not yet addressed.
 *
 * <p>Every sender on {@link NotificationService} before this one was a whole method per event,
 * with the leave request it was about hard-wired into its signature. That is workable while every
 * event is a leave event and stops being workable at the first one that is not: a caller outside
 * the leave package cannot describe what it wants to say without a method being written for it
 * inside a package it has nothing to do with.
 *
 * <p>Splitting the message from the recipient is the other half of it. A notification aimed at a
 * role is a fan-out, because {@code Notification.recipient} is a single employee and there is no
 * broadcast row, so the same draft has to be delivered several times over. Composing it once and
 * addressing it N times is what {@link NotificationService#deliverToAll} does, and it keeps the
 * wording of an event in one place rather than once per recipient.
 *
 * @param type What kind of event this is. Drives grouping and filtering in the inbox.
 * @param title The one-line heading. At most 200 characters, which the column enforces.
 * @param message The body. At most 1000 characters, which the column enforces.
 * @param entityType What the notification is about, as a bare string: {@code "MATERIAL"},
 *         {@code "LEAVE_REQUEST"}. Nullable, for a notification about nothing in particular.
 * @param entityId The id of that thing, in the recipient's own organization. Nullable with
 *         {@code entityType}.
 * @param actionUrl Where the recipient should be sent to act on it. Nullable.
 */
public record NotificationDraft(
        NotificationType type,
        String title,
        String message,
        String entityType,
        Long entityId,
        String actionUrl) {
}

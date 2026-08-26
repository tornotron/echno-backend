package org.tornotron.echno_backend.chat.realtime;

/**
 * The kinds of chat change a connected client is told about.
 *
 * <p>Deliberately coarse. {@link #MESSAGE_UPDATED} covers an edit, a soft delete and a
 * reaction toggle alike, because the client's response to all three is identical: invalidate
 * the room's message query and refetch. A finer vocabulary would have no consumer.
 */
public enum ChatEventType {

    /** A new message was posted to the room. */
    MESSAGE_CREATED,

    /** An existing message changed: edited, soft deleted, or its reactions changed. */
    MESSAGE_UPDATED,

    /** Room level state changed: archive toggled, or the caller's read marker advanced. */
    ROOM_UPDATED
}

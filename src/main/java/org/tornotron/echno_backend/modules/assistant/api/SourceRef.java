package org.tornotron.echno_backend.modules.assistant.api;

/**
 * Where a piece of evidence came from, so a user can open it: a record in the system, or a place in
 * an uploaded document.
 *
 * @param type      what kind of thing it is, for example {@code project-attendance} or {@code attachment}
 * @param id        its identifier within that type
 * @param page      the page in a document, or null
 * @param charStart where in the page's text the cited passage starts, or null
 * @param charEnd   where it ends, or null
 */
public record SourceRef(String type, String id, Integer page, Integer charStart, Integer charEnd) {

    public SourceRef {
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("A source needs a type and an id");
        }
    }

    /** A record in the system. */
    public static SourceRef entity(String type, String id) {
        return new SourceRef(type, id, null, null, null);
    }

    /** A passage of an uploaded document. */
    public static SourceRef document(long attachmentId, Integer page, Integer charStart, Integer charEnd) {
        return new SourceRef("attachment", Long.toString(attachmentId), page, charStart, charEnd);
    }
}

package org.tornotron.echno_backend.search;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of a quick-search result: enough to render a line and follow it, and nothing else.
 *
 * <p>The consumer is a navigation palette, so a hit needs a kind to pick an icon by, a label to
 * show, and the ids needed to build a link. Returning the full DTO of each entity instead would put
 * the transfer cost back where this endpoint exists to remove it, and would leak fields the palette
 * has no reason to hold.
 *
 * @param type      Which kind of record this is.
 * @param id        The record's own id.
 * @param title     The record's display name.
 * @param projectId The project the record hangs off, so a link can be built without a second
 *                  lookup. Equal to {@code id} for a project, and null for an issue that was
 *                  raised outside any task.
 */
@Schema(description = "A single quick-search hit: the minimum needed to show a result and link to it.")
public record SearchHit(

        @Schema(description = "Which kind of record this is.", example = "TASK")
        SearchHitType type,

        @Schema(description = "The record's own id.", example = "108")
        Long id,

        @Schema(description = "The record's display name.", example = "Pour foundation slab, block A")
        String title,

        @Schema(description = "Project the record belongs to, for building the link.", example = "42")
        Long projectId) {
}

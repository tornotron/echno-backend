package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** What a copy into checklist templates did: each template changed, and the outcome of each row asked for. */
@Schema(description = "The checklist templates the rows were copied into, and the outcome of each row.")
public record CopyToTemplateResultDto(List<TemplateCopy> templates, List<RowOutcome> rows) {

    /** How a row fared. */
    public enum Outcome {
        COPIED, ALREADY_COPIED, NO_TRADE, NOT_FOUND
    }

    /**
     * One template the call changed.
     *
     * @param created Whether the call defined the template, the trade having none
     * @param active Whether new inspections are created from it; an inactive template keeps the items unused
     */
    @Schema(description = "A checklist template the call added check points to.")
    public record TemplateCopy(UUID templateId, UUID tradeId, String tradeName, String templateName, int version,
                               boolean created, boolean active, int itemsAdded) {
    }

    @Schema(description = "The outcome of one row asked for.")
    public record RowOutcome(UUID requirementId, Outcome outcome,
                             @Schema(nullable = true) UUID templateId,
                             @Schema(nullable = true) UUID templateItemId,
                             @Schema(nullable = true) String message) {
    }
}

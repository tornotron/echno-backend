package org.tornotron.echno_backend.modules.inspections.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "What happened to each item, in the order posted.")
public record IntakeBatchResponse(
        int created,
        int existing,
        int rejected,
        List<Item> results
) {

    public enum Status { CREATED, EXISTING, REJECTED }

    @Schema(description = "One item's outcome.")
    public record Item(
            int index,
            @Schema(nullable = true) String externalRef,
            Status status,
            @Schema(description = "Set unless the item was rejected.", nullable = true) UUID observationId,
            @Schema(description = "Set when the item was rejected.", nullable = true) Error error
    ) {}

    @Schema(description = "Why an item was refused.")
    public record Error(
            @Schema(description = "VALIDATION, PROJECT_NOT_FOUND, INSPECTION_NOT_FOUND, ATTACHMENT_NOT_FOUND, ATTACHMENT_NOT_ALLOWED, "
                    + "INVALID_GEOMETRY, UNKNOWN_MODEL_VERSION, MODEL_NOT_ACTIVE, UNKNOWN_CLASS, SHAPE_MISMATCH, "
                    + "DUPLICATE_IN_BATCH or INTERNAL.")
            String code,
            @Schema(nullable = true) String field,
            String message
    ) {}
}

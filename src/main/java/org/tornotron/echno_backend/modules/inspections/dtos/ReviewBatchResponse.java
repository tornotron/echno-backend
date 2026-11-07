package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "What happened to each observation, in the order posted. Each is its own transaction.")
public record ReviewBatchResponse(
        @Schema(description = "Stamped on every reviewed row and its event, so an audit can tell a bulk decision "
                + "from a single one.")
        UUID batchId,
        List<Result> results
) {

    public enum Status { REVIEWED, ALREADY_REVIEWED, NOT_FOUND, REFUSED }

    @Schema(description = "One observation's outcome.")
    public record Result(
            UUID observationId,
            Status status,
            @Schema(description = "Why, when it was refused.", nullable = true) String message
    ) {}
}

package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.inspections.ObservationRejectReason;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewDecision;

@Schema(description = "One decision for the observations a reviewer has looked at. ACCEPT applies outcome NONE; REJECT "
        + "takes one note and one reason for all of them. MODIFY is refused: it needs the single-item review, where "
        + "the reviewer sees the whole photo. There is no accept-by-filter: the client sends the ids.")
public record ReviewBatchRequest(
        @Schema(description = "ACCEPT or REJECT.")
        @NotNull ObservationReviewDecision decision,
        @NotEmpty @Size(max = 100) List<UUID> observationIds,
        @Schema(description = "Reviewer's note. Required on REJECT.", nullable = true)
        @Size(max = 4000) String note,
        @Schema(description = "REJECT only. Required for each observation that has regions from its producer.",
                nullable = true)
        ObservationRejectReason rejectReason
) {}

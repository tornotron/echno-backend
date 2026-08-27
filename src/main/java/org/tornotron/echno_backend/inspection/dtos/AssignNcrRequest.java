package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Schema(description = "Payload to assign a non-conformance report to a site engineer.")
public record AssignNcrRequest(
        @Schema(description = "Id of the site engineer who owns the corrective work.", example = "8")
        @NotNull Long siteEngineerId,
        @Schema(description = "Date the corrective work is due.", example = "2026-09-10")
        LocalDate targetDate
) {}

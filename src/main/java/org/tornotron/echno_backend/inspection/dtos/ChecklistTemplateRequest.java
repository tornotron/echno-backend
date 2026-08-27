package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.inspection.InspectionTrade;

import java.util.List;

/**
 * Creates or fully replaces an organization's checklist template for a trade. The
 * check points are rebuilt from the payload on every save, so a replacement carries
 * the whole list rather than a delta. The version is a server-side revision counter
 * and is not accepted here.
 */
@Schema(description = "Payload to define or replace a checklist template for one trade.")
public record ChecklistTemplateRequest(
        @Schema(description = "Trade the checklist covers. One template per trade per organization.",
                example = "reinforcement")
        @NotNull InspectionTrade trade,
        @Schema(description = "Name of the checklist.", example = "Reinforcement inspection checklist")
        @NotBlank @Size(max = 200) String name,
        @Schema(description = "What the checklist covers and when it is used.",
                example = "Pre-pour reinforcement check for slabs, beams and columns")
        String description,
        @Schema(description = "Whether new inspections of this trade are created from this template. "
                + "Defaults to true when omitted.", example = "true")
        Boolean active,
        @Schema(description = "Check points, in the order they are carried out.")
        @NotEmpty @Valid List<ChecklistTemplateItemRequest> items
) {}

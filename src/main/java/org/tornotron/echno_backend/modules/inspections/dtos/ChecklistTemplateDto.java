package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import org.tornotron.echno_backend.project.enums.ProjectType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "An organization's checklist template for one trade, with its check points.")
public record ChecklistTemplateDto(
        UUID id,
        @Schema(description = "Slug of the trade: the same string the enum always put on the wire for the "
                + "sixteen shipped trades, or the code of an org-defined trade.", example = "reinforcement")
        String trade,
        @Schema(description = "Id of the organization's trade row.")
        UUID tradeId,
        String tradeName,
        @Schema(description = "Group code of the trade.") String tradeGroup,
        String name,
        @Schema(description = "What the template covers. Null where none was recorded, including "
                + "on a template adopted from a starter checklist that carried none.",
                nullable = true)
        String description,
        @Schema(description = "Element type codes the template suits; null means any.", nullable = true)
        List<String> applicableElementTypes,
        @Schema(description = "Project types the template suits; null means any.", nullable = true)
        List<ProjectType> applicableProjectTypes,
        boolean active,
        int version,
        List<ChecklistTemplateItemDto> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

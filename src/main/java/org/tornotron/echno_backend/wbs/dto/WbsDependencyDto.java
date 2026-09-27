package org.tornotron.echno_backend.wbs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.wbs.enums.WbsDependencyType;

@Schema(description = "A link between two activities of a project's schedule. Recorded information: no date moves because of it.")
public record WbsDependencyDto(
        @Schema(description = "Id of the link.", example = "9")
        Long id,
        @Schema(description = "Id of the activity that comes first.", example = "41")
        Long predecessorId,
        @Schema(description = "wbsCode of the activity that comes first.", example = "1.2.1")
        String predecessorWbsCode,
        @Schema(description = "Id of the activity that depends on the predecessor.", example = "42")
        Long successorId,
        @Schema(description = "wbsCode of the dependent activity.", example = "1.2.2")
        String successorWbsCode,
        @Schema(description = "FS finish-to-start, SS start-to-start, FF finish-to-finish, SF start-to-finish.", example = "FS")
        WbsDependencyType type,
        @Schema(description = "Lag in days; negative for a lead.", example = "2")
        Integer lagDays
) {}

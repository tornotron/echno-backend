package org.tornotron.echno_backend.indent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Payload to partially update an indent's number, project, status, expected date or remarks.")
@Data
public class IndentUpdateDto {
    @Schema(description = "Updated indent number.", example = "IND-2026-0015")
    private String indentNumber;

    @Schema(description = "Id of the employee who raised the indent.", example = "8")
    private Long createdByEmployeeId;

    @Schema(description = "Id of the project the indent is raised for.", example = "3")
    private Long projectId;

    @Schema(description = "Updated lifecycle status.", example = "ORDERED")
    private String status;

    @Schema(description = "Updated expected delivery date.", example = "2026-02-08T00:00:00")
    private LocalDateTime expectedOn;

    @Schema(description = "Updated remarks.", example = "Vendor confirmed dispatch for 2026-02-06")
    private String remarks;
}

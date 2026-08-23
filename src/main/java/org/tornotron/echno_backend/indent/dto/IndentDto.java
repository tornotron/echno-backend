package org.tornotron.echno_backend.indent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indent.enums.IndentStatus;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A material indent with its creator, project and requested items.")
@Data
public class IndentDto {
    @Schema(description = "Indent id.", example = "42")
    private Long id;

    @Schema(description = "Indent number.", example = "IND-2026-0015")
    private String indentNumber;

    @Schema(description = "Timestamp the indent was created.", example = "2026-01-15T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Employee who raised the indent.")
    private EmployeeDto createdBy;

    @Schema(description = "Id of the project the indent is raised for.", example = "3")
    private Long projectId;

    @Schema(description = "Name of the project.", example = "Asset Homes Perumbavoor Phase 2")
    private String projectName;

    @Schema(description = "Current lifecycle status of the indent.", example = "PENDING")
    private IndentStatus status;

    @Schema(description = "Date the materials are expected on site.", example = "2026-02-05T00:00:00")
    private LocalDateTime expectedOn;

    @Schema(description = "Free-text remarks on the indent.", example = "Required before slab pour on 2026-02-08")
    private String remarks;

    @Schema(description = "Requested items on the indent.")
    private List<IndentItemDto> items;
}

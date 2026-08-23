package org.tornotron.echno_backend.indent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Payload to raise a material indent on a project, with its requested items.")
@Data
public class IndentCreationDto {

    @Schema(description = "Indent number, unique per organization.", example = "IND-2026-0015")
    @NotBlank(message = "indentNumber is required(type: String)")
    @Size(min = 1, max = 50, message = "indentNumber must be between 1 and 50 characters")
    private String indentNumber;

    @Schema(description = "Id of the project the indent is raised for.", example = "3")
    @NotNull(message = "project ID is required")
    private Long projectId;

    @Schema(description = "Id of the employee raising the indent.", example = "8")
    @NotNull(message = "createdBy is required(type: Long)")
    private Long createdByEmployeeId;

    @Schema(description = "Lifecycle status of the indent.", example = "PENDING")
    @NotBlank(message = "status is required(type: String)")
    private String status;

    @Schema(description = "Date the materials are expected on site.", example = "2026-02-05T00:00:00")
    private LocalDateTime expectedOn;

    @Schema(description = "Free-text remarks on the indent.", example = "Required before slab pour on 2026-02-08")
    private String remarks;

    @Schema(description = "Material items being requested.")
    @Valid
    private List<IndentItemCreationDto> items;
}

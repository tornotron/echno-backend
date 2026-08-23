package org.tornotron.echno_backend.materialConsumption.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Payload to record material consumed against a project. Stock sufficiency is "
        + "checked at the material and project level, or at the storage location level when one is given.")
@Data
public class MaterialConsumptionCreationDto {

    @Schema(description = "Date and time the material was consumed.", example = "2026-01-15T00:00:00")
    @NotNull(message = "consumption date is required")
    private LocalDateTime consumptionDate;

    @Schema(description = "Id of the material consumed.", example = "12")
    @NotNull(message = "material ID is required")
    private Long materialId;

    @Schema(description = "Quantity consumed, in the material's unit of measure.", example = "50")
    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity;

    @Schema(description = "Type of consumption.", example = "NORMAL_USAGE")
    @NotBlank(message = "consumption type is required")
    private String consumptionType;

    @Schema(description = "Free-text note about the consumption.", example = "Used for column casting at 3rd floor")
    @Size(max = 500, message = "details must not exceed 500 characters")
    private String details;

    @Schema(description = "Id of the project the material was consumed against.", example = "3")
    @NotNull(message = "project ID is required")
    private Long projectId;

    @Schema(description = "Id of the storage location the material was drawn from, if applicable.", example = "2")
    private Long storageLocationId;

    @Schema(description = "Id of the task the consumption is linked to, if applicable.", example = "45")
    private Long taskId;

    @Schema(description = "Id of the employee recording this consumption.", example = "5")
    @NotNull(message = "created by employee id is required")
    private Long createdBy;
}

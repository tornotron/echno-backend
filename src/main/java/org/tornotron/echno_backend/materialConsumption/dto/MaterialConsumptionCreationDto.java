package org.tornotron.echno_backend.materialConsumption.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MaterialConsumptionCreationDto {

    @NotNull(message = "consumption date is required")
    private LocalDateTime consumptionDate;

    @NotNull(message = "material ID is required")
    private Long materialId;

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity;

    @NotBlank(message = "consumption type is required")
    private String consumptionType;

    @Size(max = 500, message = "details must not exceed 500 characters")
    private String details;

    @NotNull(message = "project ID is required")
    private Long projectId;

    @NotNull(message = "created by employee id is required")
    private Long createdBy;
}

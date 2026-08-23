package org.tornotron.echno_backend.materialConsumption.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.time.LocalDateTime;

@Schema(description = "A recorded material consumption, with its material, project, storage location "
        + "and task resolved to names.")
@Data
public class MaterialConsumptionDto {

    @Schema(description = "Consumption record id.", example = "78")
    private Long id;
    @Schema(description = "Date and time the material was consumed.", example = "2026-01-15T00:00:00")
    private LocalDateTime consumptionDate;
    @Schema(description = "Id of the material consumed.", example = "12")
    private Long materialId;
    @Schema(description = "Name of the material consumed.", example = "OPC 53 Grade Cement")
    private String materialName;
    @Schema(description = "Quantity consumed, in the material's unit of measure.", example = "50")
    private Integer quantity;
    @Schema(description = "Type of consumption.", example = "NORMAL_USAGE")
    private MaterialConsumptionType consumptionType;
    @Schema(description = "Free-text note about the consumption.", example = "Used for column casting at 3rd floor")
    private String details;
    @Schema(description = "Id of the project the material was consumed against.", example = "3")
    private Long projectId;
    @Schema(description = "Name of the project the material was consumed against.", example = "Asset Homes - Kochi Phase 2")
    private String projectName;
    @Schema(description = "Id of the storage location the material was drawn from, if applicable.", example = "2")
    private Long storageLocationId;
    @Schema(description = "Name of the storage location the material was drawn from, if applicable.", example = "Main Site Store")
    private String storageLocationName;
    @Schema(description = "Id of the task the consumption is linked to, if applicable.", example = "45")
    private Long taskId;
    @Schema(description = "Title of the task the consumption is linked to, if applicable.", example = "Column casting - Block A")
    private String taskTitle;
    @Schema(description = "Employee who recorded this consumption.")
    private EmployeeDto createdBy;
}

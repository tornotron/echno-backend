package org.tornotron.echno_backend.asset.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Payload to create or fully update a fixed asset in the organization's asset "
        + "register, such as a piece of heavy equipment or a vehicle.")
@Data
public class AssetCreationDto {

    @Schema(description = "Organization-assigned asset code.", example = "AST-0021")
    private String assetId;

    @Schema(description = "Display name of the asset.", example = "JCB 3DX Backhoe Loader")
    @NotBlank
    private String name;

    @Schema(description = "Free-text description of the asset.", example = "Backhoe loader used for excavation and material handling on site.")
    private String description;
    @Schema(description = "Asset type, a kebab-case value defined by the frontend.", example = "heavy-equipment")
    private String type;
    @Schema(description = "Asset category, a kebab-case value defined by the frontend.", example = "earthmoving")
    private String category;
    @Schema(description = "Current lifecycle status, a kebab-case value defined by the frontend.", example = "in-use")
    private String status;
    // The web sends the field as "condition"; the entity column is
    // "asset_condition" (condition is a SQL reserved word). Accept both.
    @Schema(description = "Physical condition of the asset. Accepted in the request body as either "
            + "\"assetCondition\" or \"condition\".", example = "good")
    @JsonAlias("condition")
    private String assetCondition;
    @Schema(description = "Date the asset was purchased.", example = "2023-06-10")
    private LocalDate purchaseDate;
    @Schema(description = "Purchase price of the asset.", example = "3500000.00")
    private BigDecimal purchasePrice;
    @Schema(description = "Current book value of the asset.", example = "2800000.00")
    private BigDecimal currentValue;
    @Schema(description = "Annual depreciation rate as a percentage.", example = "10.0")
    private Double depreciationRate;
    @Schema(description = "Name of the person the asset is currently assigned to.", example = "Ravi Kumar")
    private String assignedTo;
    @Schema(description = "Id of the employee the asset is currently assigned to, stored alongside the "
            + "name so the asset list can be filtered by assignee.", example = "18")
    private Long assignedToId;
    @Schema(description = "Id of the project the asset is deployed on. Changing it records a "
            + "movement in the asset's ledger; the movement's reason comes from movementReason.",
            example = "5")
    private Long assignedProjectId;
    @Schema(description = "Manufacturer of the asset.", example = "JCB India")
    private String manufacturer;
    @Schema(description = "Model name or number.", example = "3DX")
    private String model;
    @Schema(description = "Manufacturer serial number.", example = "JCB3DX2023-0456")
    private String serialNumber;
    @Schema(description = "Government registration number, for registered vehicles and equipment.", example = "KL-07-AB-1234")
    private String registrationNumber;
    @Schema(description = "Date the manufacturer warranty expires.", example = "2026-06-10")
    private LocalDate warrantyExpiry;
    @Schema(description = "Date the asset was last serviced.", example = "2026-07-01")
    private LocalDate lastMaintenanceDate;
    @Schema(description = "Date the next scheduled service is due.", example = "2026-10-01")
    private LocalDate nextMaintenanceDate;
    @Schema(description = "Date the insurance policy expires.", example = "2027-06-10")
    private LocalDate insuranceExpiry;
    @Schema(description = "How often the asset is serviced.", example = "Quarterly")
    private String maintenanceSchedule;
    @Schema(description = "Total hours the asset has been used.", example = "1250.5")
    private Double usageHours;
    @Schema(description = "Maximum usage hours before the asset is due for major overhaul or replacement.", example = "10000.0")
    private Double maxUsageHours;
    @Schema(description = "Fuel type the asset runs on.", example = "diesel")
    private String fuelType;
    @Schema(description = "Name of the insurance provider.", example = "ICICI Lombard")
    private String insuranceProvider;
    @Schema(description = "Insurance policy number.", example = "POL-887766")
    private String policyNumber;
    @Schema(description = "Free-text notes about the asset.", example = "Hydraulic hose replaced during last service.")
    private String notes;
    @Schema(description = "Id of the vendor the asset was purchased from.", example = "3")
    private Long vendorId;
    @Schema(description = "Id of the storage location the asset is stored at. Changing it records a "
            + "movement in the asset's ledger.", example = "7")
    private Long locationId;

    @Schema(description = "Why the asset is where this payload says it is. Recorded against the "
            + "movement this create or update appends to the asset's ledger. Optional here so the "
            + "existing asset form keeps working; when it is left out the entry says the movement "
            + "was recorded from an asset edit rather than inventing a reason. Prefer the dedicated "
            + "movements endpoint, which requires one.",
            example = "Mobilised to the Marina Heights site for the piling phase")
    private String movementReason;

    @Schema(description = "When the asset actually moved, if that is not now. Recorded as the "
            + "movement date so a movement entered late still sits in the right place in the ledger.",
            example = "2026-08-20T09:00:00")
    private java.time.LocalDateTime movedAt;
}

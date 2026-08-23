package org.tornotron.echno_backend.asset.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "A fixed asset in the organization's asset register, with its resolved vendor and storage location.")
@Data
public class AssetDto {
    @Schema(description = "Database id of the asset.", example = "12")
    private Long id;
    @Schema(description = "Organization-assigned asset code.", example = "AST-0021")
    private String assetId;
    @Schema(description = "Display name of the asset.", example = "JCB 3DX Backhoe Loader")
    private String name;
    @Schema(description = "Free-text description of the asset.", example = "Backhoe loader used for excavation and material handling on site.")
    private String description;
    @Schema(description = "Asset type, a kebab-case value defined by the frontend.", example = "heavy-equipment")
    private String type;
    @Schema(description = "Asset category, a kebab-case value defined by the frontend.", example = "earthmoving")
    private String category;
    @Schema(description = "Current lifecycle status, a kebab-case value defined by the frontend.", example = "in-use")
    private String status;
    @Schema(description = "Physical condition of the asset.", example = "good")
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
    @Schema(description = "Id of the employee the asset is currently assigned to, if known.", example = "18")
    private Long assignedToId;
    @Schema(description = "Name of the project the asset is currently deployed on.", example = "Marina Heights Towers")
    private String assignedProject;
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
    @Schema(description = "Name of the vendor the asset was purchased from.", example = "BuildTech Equipment Rentals")
    private String vendorName;
    @Schema(description = "Id of the storage location the asset is stored at.", example = "7")
    private Long locationId;
    @Schema(description = "Name of the storage location the asset is stored at.", example = "Kochi Yard")
    private String locationName;
    @Schema(description = "Id of the organization that owns the asset.", example = "1")
    private Long organizationId;
    @Schema(description = "Timestamp the asset record was created.", example = "2023-06-10T09:30:00")
    private LocalDateTime createdAt;
    @Schema(description = "Timestamp the asset record was last updated.", example = "2026-07-01T14:15:00")
    private LocalDateTime updatedAt;
}

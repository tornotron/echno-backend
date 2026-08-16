package org.tornotron.echno_backend.asset.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class AssetCreationDto {

    private String assetId;

    @NotBlank
    private String name;

    private String description;
    private String type;
    private String category;
    private String status;
    // The web sends the field as "condition"; the entity column is
    // "asset_condition" (condition is a SQL reserved word). Accept both.
    @JsonAlias("condition")
    private String assetCondition;
    private LocalDate purchaseDate;
    private BigDecimal purchasePrice;
    private BigDecimal currentValue;
    private Double depreciationRate;
    private String assignedTo;
    private String assignedProject;
    private String manufacturer;
    private String model;
    private String serialNumber;
    private String registrationNumber;
    private LocalDate warrantyExpiry;
    private LocalDate lastMaintenanceDate;
    private LocalDate nextMaintenanceDate;
    private LocalDate insuranceExpiry;
    private String maintenanceSchedule;
    private Double usageHours;
    private Double maxUsageHours;
    private String fuelType;
    private String insuranceProvider;
    private String policyNumber;
    private String notes;
    private Long vendorId;
    private Long locationId;
}

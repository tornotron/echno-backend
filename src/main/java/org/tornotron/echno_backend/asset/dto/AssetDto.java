package org.tornotron.echno_backend.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AssetDto {
    private Long id;
    private String assetId;
    private String name;
    private String description;
    private String type;
    private String category;
    private String status;
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
    private String vendorName;
    private Long locationId;
    private String locationName;
    private Long organizationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

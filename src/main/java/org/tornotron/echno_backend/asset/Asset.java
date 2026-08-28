package org.tornotron.echno_backend.asset;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.vendor.Vendor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A fixed asset in the organization's asset register. Type/status/condition are stored as
 * plain strings because the web client sends kebab-case values (e.g. {@code heavy-equipment})
 * that are not valid Java enum identifiers; the frontend validates them.
 *
 * <p>Where the asset is and who holds it ({@link #assignedProject}, {@link #location},
 * {@link #assignedTo}, {@link #assignedToId}) is not a second source of truth beside the
 * movement ledger. Those four columns are a cache of the latest {@link AssetMovement}, and
 * every write path goes through the one method that appends the entry and sets them together,
 * so they cannot come to disagree with the history.
 */
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Asset implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id")
    private String assetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "type")
    private String type;

    @Column(name = "category")
    private String category;

    @Column(name = "status")
    private String status;

    @Column(name = "asset_condition")
    private String assetCondition;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "purchase_price", precision = 15, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "current_value", precision = 15, scale = 2)
    private BigDecimal currentValue;

    @Column(name = "depreciation_rate")
    private Double depreciationRate;

    /**
     * Name of the current custodian. Ledger-derived: written only by
     * {@code AssetService.applyPlacement}, alongside an {@link AssetMovement}.
     */
    @Column(name = "assigned_to")
    private String assignedTo;

    /** Id of the current custodian. Ledger-derived, as {@link #assignedTo}. */
    @Column(name = "assigned_to_id")
    private Long assignedToId;

    /**
     * The project the asset is currently deployed on. Ledger-derived: written only by
     * {@code AssetService.applyPlacement}, alongside an {@link AssetMovement}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_project_id")
    private Project assignedProject;

    /**
     * The free-text project name this asset carried before {@link #assignedProject} became a
     * reference. Read-only: mapped {@code insertable = false, updatable = false} so nothing can
     * write it again, which is what makes the reference migration reversible and keeps a value
     * that matched no project from being lost. The same text is also copied into the asset's
     * opening ledger entry.
     */
    @Column(name = "assigned_project", insertable = false, updatable = false)
    private String legacyAssignedProject;

    @Column(name = "manufacturer")
    private String manufacturer;

    @Column(name = "model")
    private String model;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "warranty_expiry")
    private LocalDate warrantyExpiry;

    @Column(name = "last_maintenance_date")
    private LocalDate lastMaintenanceDate;

    @Column(name = "next_maintenance_date")
    private LocalDate nextMaintenanceDate;

    @Column(name = "insurance_expiry")
    private LocalDate insuranceExpiry;

    @Column(name = "maintenance_schedule")
    private String maintenanceSchedule;

    @Column(name = "usage_hours")
    private Double usageHours;

    @Column(name = "max_usage_hours")
    private Double maxUsageHours;

    @Column(name = "fuel_type")
    private String fuelType;

    @Column(name = "insurance_provider")
    private String insuranceProvider;

    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    /**
     * The storage location the asset currently sits at. Ledger-derived, as
     * {@link #assignedProject}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private StorageLocation location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

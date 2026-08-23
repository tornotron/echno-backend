package org.tornotron.echno_backend.inventoryTransaction;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.task.Task;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One entry in the append-only ledger of stock movements.
 *
 * <p>Every stock change (opening balance, goods received, consumption, site transfer)
 * writes a row here recording the material, project, storage location, the signed
 * quantity changed, and the opening and closing stock around it. Rows are never edited
 * or deleted, so the ledger is the audit trail from which stock can be recomputed.
 */
@Data
@Entity
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class InventoryTransaction implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    @ManyToOne
    private Material material;

    @Column(name = "opening_stock")
    private Double openingStock;

    @Column(name = "quantity_changed")
    private Double quantityChanged;

    @Column(name = "closing_stock")
    private Double closingStock;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private InventoryTransactionType transactionType;

    @Column(name = "reference_number",nullable = false)
    private String referenceNumber;

    private String remarks;

    @ManyToOne(fetch = FetchType.LAZY)
    private Employee createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id")
    private StorageLocation storageLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @Column(name = "unit_cost", precision = 15, scale = 2)
    private BigDecimal unitCost;

    @CreationTimestamp
    private LocalDateTime createdAt;
}

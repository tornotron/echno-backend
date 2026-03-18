package org.tornotron.echno_backend.wbs;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.intend.Intend;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.wbs.enums.WbsStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "wbs_element", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"project_id", "wbs_code"})
})
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class WbsElement implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wbs_code", nullable = false, length = 50)
    private String wbsCode;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "level", nullable = false)
    private Integer level = 0;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WbsStatus status = WbsStatus.NOT_STARTED;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "actual_start_date")
    private LocalDate actualStartDate;

    @Column(name = "actual_end_date")
    private LocalDate actualEndDate;

    @Column(name = "budgeted_cost", precision = 15, scale = 2)
    private BigDecimal budgetedCost = BigDecimal.ZERO;

    @Column(name = "actual_cost", precision = 15, scale = 2)
    private BigDecimal actualCost = BigDecimal.ZERO;

    @Column(name = "progress")
    private Double progress = 0.0;

    @Column(name = "weight")
    private Double weight = 1.0;

    @Column(name = "is_leaf", nullable = false)
    private Boolean isLeaf = true;

    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private WbsElement parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<WbsElement> children = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Employee createdBy;

    @OneToMany(mappedBy = "wbsElement")
    private List<Task> tasks = new ArrayList<>();

    @OneToMany(mappedBy = "wbsElement")
    private List<MaterialConsumption> materialConsumptions = new ArrayList<>();

    @OneToMany(mappedBy = "wbsElement")
    private List<PurchaseOrder> purchaseOrders = new ArrayList<>();

    @OneToMany(mappedBy = "wbsElement")
    private List<Payable> payables = new ArrayList<>();

    @OneToMany(mappedBy = "wbsElement")
    private List<Intend> intends = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

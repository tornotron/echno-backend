package org.tornotron.echno_backend.materialConsumption;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.wbs.WbsElement;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class MaterialConsumption implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumption_date", nullable = false)
    private LocalDateTime consumptionDate;

    @ManyToOne
    private Material material;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "consumption_type", nullable = false)
    private MaterialConsumptionType consumptionType;

    private String details;

    @ManyToOne(fetch = FetchType.LAZY)
    private Employee createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id")
    private StorageLocation storageLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wbs_element_id")
    private WbsElement wbsElement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}

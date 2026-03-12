package org.tornotron.echno_backend.intend;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.intend.enums.IntendStatus;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@NoArgsConstructor
@Data
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Intend implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intend_number", nullable = false, unique = true)
    private String intendNumber;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

//    @ManyToOne
//    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    private Employee createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IntendStatus status;

    @Column(name = "expected_on")
    private LocalDateTime expectedOn;

    @Column(name = "remark")
    private String remarks;

    @OneToMany(mappedBy = "intend")
    private List<IndentItem> items;

    @OneToMany(mappedBy = "intend")
    private List<PurchaseOrder> purchaseOrders = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}

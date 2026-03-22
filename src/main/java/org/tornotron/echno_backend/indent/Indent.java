package org.tornotron.echno_backend.indent;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.indent.enums.IndentStatus;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.wbs.WbsElement;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@NoArgsConstructor
@Data
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Indent implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "indent_number", nullable = false, unique = true)
    private String indentNumber;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    private Employee createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IndentStatus status;

    @Column(name = "expected_on")
    private LocalDateTime expectedOn;

    @Column(name = "remark")
    private String remarks;

    @OneToMany(mappedBy = "indent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IndentItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "indent")
    private List<PurchaseOrder> purchaseOrders = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wbs_element_id")
    private WbsElement wbsElement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    public void addItem(IndentItem item) {
        items.add(item);
        item.setIndent(this);
    }
}

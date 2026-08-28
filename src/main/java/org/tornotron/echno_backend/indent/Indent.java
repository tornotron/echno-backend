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

/**
 * A request from a project site for materials to be procured.
 *
 * <p>Lists the wanted materials and quantities as {@link IndentItem} lines and moves
 * through a status lifecycle. An indent is the demand signal that a purchase order is
 * later raised to fulfil; items carry a flag once they have been converted to a PO.
 */
@Entity
@NoArgsConstructor
@Data
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Indent implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Allocated by the server, never by the client. Unique per organization rather than
     * globally: the constraint that enforces it is {@code uk_indent_org_number} in the schema, and it
     * is composite, so it cannot be declared here as a column-level unique.
     */
    @Column(name = "indent_number", nullable = false)
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

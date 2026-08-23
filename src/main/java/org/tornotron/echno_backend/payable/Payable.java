package org.tornotron.echno_backend.payable;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.payable.enums.ContractType;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.wbs.WbsElement;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An amount owed to a vendor or contractor (accounts payable).
 *
 * <p>Tracks the recorded amount against the amount already paid, with the outstanding
 * balance derived by {@link #getAmountDue()}. A payable belongs to a project and may
 * reference the originating vendor, goods-received note, and WBS element. Scoped to one
 * organization by the {@code orgFilter} tenant filter.
 */
@Data
@Entity
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Payable implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payable_number", nullable = false, unique = true)
    private String payableNumber;

    @Column(name = "contactor_name", nullable = false)
    private String contractorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type")
    private ContractType contractType;

    @Column(name = "amount_recorded", precision = 15, scale = 2)
    private BigDecimal amountRecorded;

    @Column(name = "amount_paid", precision = 15, scale = 2)
    private BigDecimal amountPaid;

    public BigDecimal getAmountDue() {
        return (amountRecorded == null ? BigDecimal.ZERO : amountRecorded)
                .subtract(amountPaid == null ? BigDecimal.ZERO : amountPaid);
    }

    @ManyToOne
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    @ManyToOne
    @JoinColumn(name = "goods_received_note_id")
    private GoodsReceivedNote goodsReceivedNote;

//    @ManyToOne
//    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    private Employee createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wbs_element_id")
    private WbsElement wbsElement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}

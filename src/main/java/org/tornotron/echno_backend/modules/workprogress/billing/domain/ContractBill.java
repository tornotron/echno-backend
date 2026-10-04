package org.tornotron.echno_backend.modules.workprogress.billing.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One bill a contractor raises on a sub-contract, running account or milestone, from the claim
 * through joint measurement and certification to final approval.
 *
 * <p>The aggregate of billing, tenant scoped behind {@code orgFilter}. A running account bill
 * owns one line per BOQ item; a milestone bill names its contract milestone and carries the
 * claimed and certified percent instead. Both own their adjustment lines. The money columns are
 * written when the bill is certified and never again: until then the figures are worked out on
 * read from the lines and rules, so they cannot drift from what the bill holds.
 */
@Entity
@Table(name = "contract_bill")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ContractBill implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "sub_contract_id", nullable = false)
    private Long subContractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_model", nullable = false, length = 20)
    private BillingModel billingModel;

    @Column(name = "bill_number", nullable = false, length = 20)
    private String billNumber;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillStatus status;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(name = "contract_milestone_id")
    private Long contractMilestoneId;

    @Column(name = "milestone_value", precision = 18, scale = 2)
    private BigDecimal milestoneValue;

    @Column(name = "claimed_percent", precision = 5, scale = 2)
    private BigDecimal claimedPercent;

    @Column(name = "certified_percent", precision = 5, scale = 2)
    private BigDecimal certifiedPercent;

    @Column(name = "contractor_reference", length = 100)
    private String contractorReference;

    @Column(length = 200)
    private String location;

    @Column(name = "measurement_date")
    private LocalDate measurementDate;

    @Column(name = "measured_by", length = 150)
    private String measuredBy;

    @Column(name = "client_representative", length = 150)
    private String clientRepresentative;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "return_reason", columnDefinition = "TEXT")
    private String returnReason;

    @Column(name = "gross_certified", precision = 18, scale = 2)
    private BigDecimal grossCertified;

    @Column(name = "previous_certified", precision = 18, scale = 2)
    private BigDecimal previousCertified;

    @Column(name = "additions_total", precision = 18, scale = 2)
    private BigDecimal additionsTotal;

    @Column(name = "deductions_total", precision = 18, scale = 2)
    private BigDecimal deductionsTotal;

    @Column(name = "net_payable", precision = 18, scale = 2)
    private BigDecimal netPayable;

    @Column(name = "prepared_by")
    private Long preparedBy;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "certified_by")
    private Long certifiedBy;

    @Column(name = "certified_at")
    private LocalDateTime certifiedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "self_approved", nullable = false)
    private boolean selfApproved;

    @Column(name = "payable_id")
    private Long payableId;

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ContractBillLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ContractBillAdjustment> adjustments = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Attaches a line, wiring the back-reference. */
    public void addLine(ContractBillLine line) {
        line.setBill(this);
        lines.add(line);
    }

    /** Attaches an adjustment, wiring the back-reference. */
    public void addAdjustment(ContractBillAdjustment adjustment) {
        adjustment.setBill(this);
        adjustments.add(adjustment);
    }
}

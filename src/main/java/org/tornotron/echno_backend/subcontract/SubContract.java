package org.tornotron.echno_backend.subcontract;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A subcontract: a header plus a list of milestones. Type and status values are
 * stored as plain strings because the web client sends kebab/camel values (e.g.
 * {@code on-hold}, {@code inProgress}) that are not valid Java enum identifiers;
 * the frontend validates them.
 *
 * <p>{@code projectId}/{@code projectName} are kept as plain columns rather than a
 * JPA relationship to keep the module self-contained; supervisor/account-manager
 * ids are likewise plain columns.
 */
@Entity
@Table(name = "sub_contract")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class SubContract implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id")
    private String contractId;

    @Column(name = "contract_name", nullable = false)
    private String contractName;

    @Column(name = "work_description", columnDefinition = "TEXT")
    private String workDescription;

    @Column(name = "scope_of_work", columnDefinition = "TEXT")
    private String scopeOfWork;

    @Column(name = "contractor_name", nullable = false)
    private String contractorName;

    @Column(name = "contractor_contact_person")
    private String contractorContactPerson;

    @Column(name = "contractor_phone")
    private String contractorPhone;

    @Column(name = "contractor_email")
    private String contractorEmail;

    @Column(name = "contractor_address", columnDefinition = "TEXT")
    private String contractorAddress;

    @Column(name = "contractor_gst")
    private String contractorGst;

    @Column(name = "contractor_pan")
    private String contractorPan;

    @Column(name = "contractor_license")
    private String contractorLicense;

    @Column(name = "type")
    private String type;

    @Column(name = "status")
    private String status;

    @Column(name = "contract_value", precision = 15, scale = 2)
    private BigDecimal contractValue;

    @Column(name = "currency")
    private String currency = "INR";

    @Column(name = "mobilization_advance", precision = 15, scale = 2)
    private BigDecimal mobilizationAdvance;

    @Column(name = "retention_percentage", precision = 15, scale = 2)
    private BigDecimal retentionPercentage;

    @Column(name = "total_paid", precision = 15, scale = 2)
    private BigDecimal totalPaid;

    @Column(name = "total_due", precision = 15, scale = 2)
    private BigDecimal totalDue;

    @Column(name = "payment_terms", columnDefinition = "TEXT")
    private String paymentTerms;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "actual_completion_date")
    private LocalDate actualCompletionDate;

    @Column(name = "completion_percentage", precision = 15, scale = 2)
    private BigDecimal completionPercentage;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "project_name")
    private String projectName;

    @Column(name = "quality_rating", precision = 15, scale = 2)
    private BigDecimal qualityRating;

    @Column(name = "timeliness_rating", precision = 15, scale = 2)
    private BigDecimal timelinessRating;

    @Column(name = "safety_rating", precision = 15, scale = 2)
    private BigDecimal safetyRating;

    @Column(name = "overall_rating", precision = 15, scale = 2)
    private BigDecimal overallRating;

    @Column(name = "insurance_provider")
    private String insuranceProvider;

    @Column(name = "insurance_policy_number")
    private String insurancePolicyNumber;

    @Column(name = "insurance_expiry")
    private LocalDate insuranceExpiry;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "bank_account_number")
    private String bankAccountNumber;

    @Column(name = "bank_ifsc")
    private String bankIfsc;

    @Column(name = "supervisor_id")
    private Long supervisorId;

    @Column(name = "account_manager_id")
    private Long accountManagerId;

    @Column(name = "penalty_clause", columnDefinition = "TEXT")
    private String penaltyClause;

    @Column(name = "warranty_period")
    private String warrantyPeriod;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @OneToMany(mappedBy = "subContract", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ContractMilestone> milestones = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Attaches a milestone to this contract, wiring the back-reference. */
    public void addMilestone(ContractMilestone milestone) {
        milestone.setSubContract(this);
        this.milestones.add(milestone);
    }
}

package org.tornotron.echno_backend.labour;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.labour.enums.EmploymentType;
import org.tornotron.echno_backend.labour.enums.SkillLevel;
import org.tornotron.echno_backend.labour.enums.Status;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A labour/worker record on a construction site.
 *
 * <p>Holds identity and contact details, emergency contact, employment type, skill level,
 * and status, along with the worker's current project assignment and pay information (daily
 * and overtime rates, bank details for disbursement). Scoped to one organization by the
 * {@code orgFilter} tenant filter.
 */
@EqualsAndHashCode(callSuper = true)
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Labour extends BaseEntity implements TenantScopedEntity {

    @Column(name = "labour_id", nullable = false)
    private String labourID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", nullable = true,unique = true)
    private String email;

    @Column(name = "address", nullable = true)
    private String address;

    @Column(name = "phone_number", nullable = false,unique = true)
    private String phoneNumber;

    @Column(name = "emergency_contact_name", nullable = true)
    private String emergencyContactName;

    @Column(name = "emergency_contact_number", nullable = true)
    private String emergencyContactNumber;

    @Column(name = "specialization", nullable = true)
    private String specialization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmploymentType employmentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SkillLevel skillLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "joining_date", nullable = false)
    private LocalDate joiningDate;

    //contractor relation

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project currentProject;

    //payment information
    @Column(name = "daily_rate")
    private BigDecimal dailyRate;

    @Column(name = "over_time_rate")
    private BigDecimal overTimeRate;

    @Column(name = "bank_account_number",unique = true)
    private String bankAccountNumber;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "ifsc_code")
    private String ifscCode;

    private String additionalNotes;
}

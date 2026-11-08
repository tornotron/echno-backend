package org.tornotron.echno_backend.modules.workprogress.billing.payment.authorizer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
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
 * The employee who authorizes payments on one project's bills. One row per project at most; no row
 * means the project has no authorizer yet, and its payments wait until someone who manages the project
 * names one. Every change is also written to {@link ProjectPaymentAuthorizerChange}.
 */
@Entity
@Table(name = "project_payment_authorizer")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ProjectPaymentAuthorizer implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /** The user who named this authorizer. */
    @Column(name = "set_by")
    private Long setBy;

    @Column(name = "set_at", nullable = false)
    private LocalDateTime setAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private int version;
}

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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One change to a project's payment authorizer: who it was, who it became, who made the change and
 * when. Append-only. A null employee on either side means none. Names are copied as they were, so the
 * history reads the same after someone leaves.
 */
@Entity
@Table(name = "project_payment_authorizer_change")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ProjectPaymentAuthorizerChange implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "previous_employee_id")
    private Long previousEmployeeId;

    @Column(name = "previous_employee_name")
    private String previousEmployeeName;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_name")
    private String employeeName;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_by_name")
    private String changedByName;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}

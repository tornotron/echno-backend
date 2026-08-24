package org.tornotron.echno_backend.finance.settings;

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
import java.time.LocalDateTime;

/**
 * Organization-level finance configuration, one row per tenant.
 *
 * <p>Holds the approval threshold that governs whether a construction invoice needs manual
 * approval. A null {@code approvalThreshold} means every invoice must be approved by hand (the
 * original behaviour); a value {@code T} means an invoice whose total is below {@code T} is
 * auto-approved on submit, and one at or above {@code T} still goes through the approval queue.
 */
@Entity
@Table(name = "finance_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_finance_settings_org",
                columnNames = {"organization_id"}))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class FinanceSettings implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /**
     * Auto-approval threshold. Null means every invoice needs manual approval; a value T means
     * invoices below T are auto-approved on submit.
     */
    @Column(name = "approval_threshold", precision = 19, scale = 4)
    private BigDecimal approvalThreshold;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

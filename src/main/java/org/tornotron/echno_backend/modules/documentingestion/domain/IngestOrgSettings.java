package org.tornotron.echno_backend.modules.documentingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
 * One organization's document ingestion settings. A missing row reads as the defaults: LLM reading
 * on, no monthly budget of its own. Each change is also written to {@link IngestSettingChange}.
 */
@Entity
@Table(name = "doc_ingest_org_settings")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class IngestOrgSettings implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "llm_reading_enabled", nullable = false)
    private boolean llmReadingEnabled = true;

    @Column(name = "monthly_budget_usd", precision = 12, scale = 2)
    private BigDecimal monthlyBudgetUsd;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

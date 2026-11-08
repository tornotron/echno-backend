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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/** One change to a document ingestion setting, append only: what it was, what it became, who, when. */
@Entity
@Table(name = "doc_ingest_setting_change")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class IngestSettingChange implements TenantScopedEntity {

    public static final String LLM_READING = "LLM_READING";
    public static final String MONTHLY_BUDGET_USD = "MONTHLY_BUDGET_USD";
    public static final String MONTHLY_BUDGET_TOKENS = "MONTHLY_BUDGET_TOKENS";
    public static final String PACKAGE_BUDGET_USD = "PACKAGE_BUDGET_USD";
    public static final String PACKAGE_BUDGET_TOKENS = "PACKAGE_BUDGET_TOKENS";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "setting", nullable = false, length = 40)
    private String setting;

    @Column(name = "old_value", length = 100)
    private String oldValue;

    @Column(name = "new_value", nullable = false, length = 100)
    private String newValue;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}

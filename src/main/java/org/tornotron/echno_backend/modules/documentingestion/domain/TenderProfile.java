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
 * The accepted facts of a tender that {@code Project} has no field for (design section 4.1), one row per
 * package. Each value is kept as printed; the typed column beside it is filled when the text reads
 * plainly as an amount, a date or a number of days, and is left empty otherwise. Written only by the
 * apply step, with provenance on each field.
 */
@Entity
@Table(name = "doc_ingest_tender_profile")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class TenderProfile implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "client", length = 500)
    private String client;

    @Column(name = "estimated_value_text", length = 500)
    private String estimatedValueText;

    @Column(name = "estimated_value", precision = 18, scale = 2)
    private BigDecimal estimatedValue;

    @Column(name = "emd_text", length = 500)
    private String emdText;

    @Column(name = "emd_amount", precision = 18, scale = 2)
    private BigDecimal emdAmount;

    @Column(name = "tender_fee_text", length = 500)
    private String tenderFeeText;

    @Column(name = "tender_fee_amount", precision = 18, scale = 2)
    private BigDecimal tenderFeeAmount;

    @Column(name = "bid_due_text", length = 500)
    private String bidDueText;

    @Column(name = "bid_due_at")
    private LocalDateTime bidDueAt;

    @Column(name = "bid_validity_text", length = 500)
    private String bidValidityText;

    @Column(name = "bid_validity_days")
    private Integer bidValidityDays;

    @Column(name = "duration_text", length = 500)
    private String durationText;

    @Column(name = "consultant", length = 500)
    private String consultant;

    @Column(name = "engineer", length = 500)
    private String engineer;

    @Column(name = "scope", length = 4000)
    private String scope;

    @Column(name = "updated_by")
    private Long updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

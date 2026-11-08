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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One row of the Contract Obligation Register (design section 4.5): an obligation with a stated deadline,
 * as the conditions of contract print it, accepted by a person. The register holds the obligations found,
 * never all of them, and Echno does not interpret the clause. Written only by the apply step, with
 * provenance on each field.
 */
@Entity
@Table(name = "doc_ingest_obligation")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ObligationEntry implements TenantScopedEntity {

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

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "clause", length = 100)
    private String clause;

    @Column(name = "responsible_party", length = 255)
    private String responsibleParty;

    @Column(name = "topic", length = 255)
    private String topic;

    @Column(name = "trigger_event", length = 1000)
    private String triggerEvent;

    @Column(name = "deadline_text", nullable = false, length = 255)
    private String deadlineText;

    /** The deadline in days, set only when it reads plainly as calendar days or weeks. */
    @Column(name = "deadline_days")
    private Integer deadlineDays;

    @Column(name = "required_document", length = 500)
    private String requiredDocument;

    @Column(name = "financial_impact", length = 1000)
    private String financialImpact;

    /** {@code GCC}, {@code SCC} or {@code CONTRACT_DATA}. */
    @Column(name = "source_class", length = 30)
    private String sourceClass;

    @Column(name = "document_id")
    private UUID documentId;

    /** The project risk raised from this obligation, if any. */
    @Column(name = "risk_id")
    private UUID riskId;

    @Column(name = "updated_by")
    private Long updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

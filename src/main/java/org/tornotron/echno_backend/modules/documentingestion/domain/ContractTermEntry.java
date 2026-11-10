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
 * One accepted contract term of a package (design section 4.5), one row per term: its value and clause
 * as printed, and the class of the part of the documents it came from. Written only by the apply step,
 * with provenance on each field. These are the main contract's terms as the tender states them; they are
 * not sub-contract fields and are never written to {@code contract_deduction_rule}.
 */
@Entity
@Table(name = "doc_ingest_contract_term")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ContractTermEntry implements TenantScopedEntity {

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

    /** The term's key, such as {@code ld_rate}. */
    @Column(name = "term", nullable = false, length = 40)
    private String term;

    @Column(name = "value_text", nullable = false, length = 1000)
    private String valueText;

    @Column(name = "clause", length = 100)
    private String clause;

    /** {@code GCC}, {@code SCC} or {@code CONTRACT_DATA}. */
    @Column(name = "source_class", length = 30)
    private String sourceClass;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    /** The project risk raised from this term, if any. */
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

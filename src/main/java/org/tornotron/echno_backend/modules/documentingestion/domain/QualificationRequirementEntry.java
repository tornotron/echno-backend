package org.tornotron.echno_backend.modules.documentingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One accepted bidder qualification requirement of a package (design section 4.7), one row per applied
 * candidate, with the values as printed. Written only by the apply step, with provenance on each field.
 * For equipment and personnel, which are text, a person sets the matrix status; who set it and when is
 * kept beside it.
 */
@Entity
@Table(name = "doc_ingest_qualification_requirement")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class QualificationRequirementEntry implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    /** {@code TURNOVER}, {@code SIMILAR_PROJECTS}, {@code EXPERIENCE}, {@code EQUIPMENT} or {@code PERSONNEL}. */
    @Column(name = "requirement", nullable = false, length = 30)
    private String requirement;

    @Column(name = "value_text", nullable = false, length = 1000)
    private String valueText;

    @Column(name = "min_value_text", length = 255)
    private String minValueText;

    @Column(name = "period_text", length = 255)
    private String periodText;

    /** {@code NIT}, {@code ITB} or {@code ELIGIBILITY}. */
    @Column(name = "source_class", length = 30)
    private String sourceClass;

    @Column(name = "document_id")
    private UUID documentId;

    /** The status a person set, for a requirement compared by hand; null until one is set. */
    @Enumerated(EnumType.STRING)
    @Column(name = "person_status", length = 20)
    private MatrixStatus personStatus;

    @Column(name = "person_note", length = 1000)
    private String personNote;

    @Column(name = "status_set_by")
    private Long statusSetBy;

    @Column(name = "status_set_at")
    private LocalDateTime statusSetAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

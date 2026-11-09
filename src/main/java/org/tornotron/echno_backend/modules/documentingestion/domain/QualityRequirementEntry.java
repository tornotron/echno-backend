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
import java.util.List;
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
 * One row of a project's inspection and test plan as the tender's specification states it (design
 * section 4.4): a quality requirement accepted by a person, each value as printed. Written only by the
 * apply step, with provenance on each field. A frequency or acceptance criterion the specification does
 * not state stays null here.
 *
 * <p>The trade is matched to one of the organization's inspection trades when the row is applied, and
 * only when the match is unambiguous. Copying the row into the trade's checklist template is a separate
 * action, which records the template and revision it went into.
 */
@Entity
@Table(name = "doc_ingest_quality_requirement")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class QualityRequirementEntry implements TenantScopedEntity {

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

    /** The material or subject as printed, the candidate's natural key. */
    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "activity", length = 255)
    private String activity;

    /** The organization's inspection trade the activity was matched to, if one matched. */
    @Column(name = "trade_id")
    private UUID tradeId;

    @Column(name = "trade_code", length = 50)
    private String tradeCode;

    @Column(name = "trade_name", length = 200)
    private String tradeName;

    @Column(name = "material", length = 255)
    private String material;

    @Column(name = "grade", length = 100)
    private String grade;

    // Text arrays like checklist_templates.applicable_element_types: a JSON-typed List<String> would be
    // cached by Hibernate for every other List<String> column.
    @Column(name = "standards", columnDefinition = "text[]")
    private List<String> standards;

    @Column(name = "test_name", length = 500)
    private String test;

    @Column(name = "frequency", length = 1000)
    private String frequency;

    @Column(name = "acceptance_criterion", length = 1000)
    private String acceptanceCriterion;

    @Column(name = "hold_or_witness_point", length = 500)
    private String holdOrWitnessPoint;

    @Column(name = "required_documents", columnDefinition = "text[]")
    private List<String> requiredDocuments;

    /** {@code SPECIFICATION}, or the class of the segment it came from. */
    @Column(name = "source_class", length = 30)
    private String sourceClass;

    @Column(name = "document_id")
    private UUID documentId;

    /** The checklist template the row was copied into, if it was. */
    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "template_item_id")
    private UUID templateItemId;

    /** The template's revision after the copy. */
    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(name = "copied_by")
    private Long copiedBy;

    @Column(name = "copied_at")
    private LocalDateTime copiedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

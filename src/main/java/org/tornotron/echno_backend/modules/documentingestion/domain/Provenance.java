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
import org.hibernate.annotations.Immutable;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One field of a live record and where its value came from. Append only: written in the transaction
 * that applies the value and never updated. A field a person filled in with nothing extracted has no
 * source and reads as entered by hand.
 */
@Entity
@Immutable
@Table(name = "doc_ingest_provenance")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class Provenance implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "target_type", nullable = false, length = 60)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Column(name = "target_field", nullable = false, length = 100)
    private String targetField;

    @Column(name = "value_extracted", columnDefinition = "TEXT")
    private String valueExtracted;

    @Column(name = "value_accepted", columnDefinition = "TEXT")
    private String valueAccepted;

    @Column(name = "entered_by_hand", nullable = false)
    private boolean enteredByHand;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "source_ref_id")
    private UUID sourceRefId;

    @Column(name = "accepted_by")
    private Long acceptedBy;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "applied_by")
    private Long appliedBy;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;
}

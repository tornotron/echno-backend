package org.tornotron.echno_backend.documentReversal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.documentReversal.enums.DocumentReversalStatus;
import org.tornotron.echno_backend.documentReversal.enums.ReversibleDocumentType;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;

/**
 * A request to undo a stock document, and the record of what became of it.
 *
 * <p>One row per request, whichever kind of document it names. The document is referenced by
 * kind and id rather than by a foreign key to one owner, the way {@code StatusTransition} and
 * {@code StockAdjustment.sourceDocument} already are: a nullable foreign key per kind ends as a
 * row of mutually exclusive columns with nothing enforcing that only one is set, and the write
 * path resolves the pair within the caller's organization before it is stored.
 *
 * <p>The document number is copied at request time so the list can be read without a join per
 * row and so the reference survives whatever later happens to the document.
 *
 * <p>Approval writes the correcting ledger entries and stamps the document itself with this
 * row's id, so the two link both ways: the document knows which reversal undid it, and the
 * reversal knows which document it undid. See {@link DocumentReversalService#approve}.
 */
@Entity
@Table(name = "document_reversal")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class DocumentReversal implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private ReversibleDocumentType documentType;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "document_number", nullable = false, length = 100)
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocumentReversalStatus status = DocumentReversalStatus.PENDING;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /** The user who asked for the reversal. Always the document's creator; see the service. */
    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    /** The user who approved, rejected or cancelled the request. Null while pending. */
    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** The approver's reason on a rejection, or a note on the other decisions. */
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    /**
     * The reference number the correcting ledger entries were written under, so the movement
     * history can be read from the reversal. Null until approved, and null on an approved
     * purchase order reversal, which writes no stock.
     */
    @Column(name = "reversal_reference", length = 100)
    private String reversalReference;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

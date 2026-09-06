package org.tornotron.echno_backend.stockAdjustment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.stockAdjustment.enums.StockAdjustmentSourceType;
import org.tornotron.echno_backend.storageLocation.StorageLocation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A stock-adjustment document: a header plus a list of line items. Type, status and
 * reason values are stored as plain strings because the web client sends kebab/snake
 * values (e.g. {@code write_off}, {@code physical_count}) that are not valid Java enum
 * identifiers; the frontend validates them.
 *
 * <p>The document is a draft until it is decided. Approval posts each line to the stock
 * ledger and moves the balance, and stamps {@code processedAt}, which is what marks the
 * document as posted: a posted document cannot be posted again, edited, or deleted. See
 * {@link StockAdjustmentService#approve}.
 *
 * <p>Rejection is the other decision. It moves no stock and writes no ledger entry; it stamps
 * {@code rejectedBy}, {@code rejectedAt} and {@code rejectionReason}, which is what marks the
 * document as refused and freezes it the same way. See {@link StockAdjustmentService#reject}.
 */
@Entity
@Table(name = "stock_adjustment")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class StockAdjustment implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "adjustment_number")
    private String adjustmentNumber;

    @Column(name = "type")
    private String type;

    @Column(name = "status")
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private StorageLocation location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(name = "adjustment_date")
    private LocalDate adjustmentDate;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "total_adjustment_value", precision = 15, scale = 2)
    private BigDecimal totalAdjustmentValue;

    @Column(name = "primary_reason")
    private String primaryReason;

    /**
     * The kind of document this adjustment was raised to answer, null when it answers none.
     *
     * <p>Stored as a name beside {@link #sourceDocumentId} rather than as a foreign key to one
     * owner, the way {@code StatusTransition} and {@code Attachment} already are. The reasoning
     * is theirs: a transfer variance is the first cause with a document behind it, a goods
     * receipt and a consumption are the obvious next two, and a nullable foreign key per cause
     * ends as a row of mutually exclusive columns with nothing enforcing that only one is set.
     *
     * <p>The difference from those two is that this is an enum rather than a free string. The set
     * of causes is closed, so every value has a resolver that loads the named document within the
     * caller's organization before the reference is written. What the database therefore does not
     * constrain, the write path does.
     *
     * <p>A posted adjustment is a ledger fact and outlives the document that caused it, which is
     * the same reason the status trail carries no foreign key: deleting the transfer must not
     * erase the correction raised against it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_document_type", length = 40)
    private StockAdjustmentSourceType sourceDocumentType;

    /**
     * The id of the document named by {@link #sourceDocumentType}, within its own kind. Not a
     * foreign key, deliberately, and never null while a type is set: the two are written together
     * or not at all, because a type with no id names nothing and an id with no type says nothing
     * about what it points at.
     */
    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    @Column(name = "justification", columnDefinition = "TEXT")
    private String justification;

    @Column(name = "physical_count_date")
    private LocalDate physicalCountDate;

    @Column(name = "physical_count_by")
    private Long physicalCountBy;

    @Column(name = "count_method")
    private String countMethod;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_by")
    private Long rejectedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    // 500 is the width the column was created with; stated here so the mapping and the schema
    // agree and a long reason is caught by validation rather than by the database.
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "total_variance_quantity")
    private Double totalVarianceQuantity;

    @OneToMany(mappedBy = "stockAdjustment", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<StockAdjustmentLineItem> lineItems = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Attaches a line item to this document, wiring the back-reference. */
    public void addLineItem(StockAdjustmentLineItem item) {
        item.setStockAdjustment(this);
        this.lineItems.add(item);
    }
}

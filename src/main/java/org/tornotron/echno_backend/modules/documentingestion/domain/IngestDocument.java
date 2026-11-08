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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One file in a package. {@code parentDocumentId} names the ZIP it came out of and
 * {@code supersedesDocumentId} the earlier revision it replaces, so the revision chain is in the
 * schema from the start. {@code outputPrefix} is where the worker's outputs are, which for a file
 * seen before in this organization is another document's prefix.
 */
@Entity
@Table(name = "doc_ingest_document")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class IngestDocument implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "parent_document_id")
    private UUID parentDocumentId;

    @Column(name = "attachment_id")
    private Long attachmentId;

    @Column(name = "source_key", nullable = false, length = 500)
    private String sourceKey;

    @Column(name = "output_prefix", length = 500)
    private String outputPrefix;

    @Column(length = 64)
    private String sha256;

    @Column(name = "file_name", nullable = false, length = 300)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 10)
    private DocumentFileType fileType;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status = DocumentStatus.AWAITING_UPLOAD;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "doc_class", length = 30)
    private String docClass;

    @Column(name = "class_set_by")
    private Long classSetBy;

    /** How the class was proposed; the class counts as confirmed once {@code classConfirmedAt} is set. */
    @Enumerated(EnumType.STRING)
    @Column(name = "class_method", length = 20)
    private ClassMethod classMethod;

    @Column(name = "class_reason", length = 300)
    private String classReason;

    @Column(name = "class_confirmed_at")
    private LocalDateTime classConfirmedAt;

    /** Pages that were not read for classification, such as pages not in English. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unread_pages", columnDefinition = "jsonb")
    private List<UnreadPage> unreadPages;

    @Column(name = "revision_label", length = 100)
    private String revisionLabel;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "supersedes_document_id")
    private UUID supersedesDocumentId;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "sheet_count")
    private Integer sheetCount;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "pages_ocr")
    private Integer pagesOcr;

    @Column(name = "pages_failed")
    private Integer pagesFailed;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;
}

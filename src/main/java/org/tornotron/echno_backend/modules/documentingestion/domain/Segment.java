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

/** A part of a document holding one kind of content: a page range of a PDF or a sheet of a workbook. */
@Entity
@Table(name = "doc_ingest_segment")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class Segment implements TenantScopedEntity {

    /** The sheet name of a segment that covers a whole workbook; Excel forbids {@code *} in sheet names. */
    public static final String WHOLE_WORKBOOK = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "doc_class", nullable = false, length = 30)
    private String docClass;

    @Column(name = "page_from")
    private Integer pageFrom;

    @Column(name = "page_to")
    private Integer pageTo;

    @Column(name = "sheet_name", length = 100)
    private String sheetName;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** Null on a segment made before classification existed, which was confirmed when it was made. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SegmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ClassMethod method;

    /** What the class was read from, such as the heading that named it, in one plain sentence. */
    @Column(length = 300)
    private String reason;

    /** Pages of the segment that were not read: not in English, not parsed, or no answer after retries. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unread_pages", columnDefinition = "jsonb")
    private List<UnreadPage> unreadPages;

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

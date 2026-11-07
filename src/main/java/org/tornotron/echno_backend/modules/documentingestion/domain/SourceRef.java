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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * Where one field of a candidate came from: the document and its hash, then a page and a box for a
 * PDF, a sheet, row and cell for a workbook, or a paragraph number for a DOCX, which has no fixed
 * pages. The box is normalized 0 to 1 as {@code [x0, y0, x1, y1]}.
 */
@Entity
@Table(name = "doc_ingest_source_ref")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class SourceRef implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(nullable = false, length = 100)
    private String field;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "document_sha256", length = 64)
    private String documentSha256;

    private Integer page;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "box", columnDefinition = "jsonb")
    private List<Double> box;

    @Column(length = 100)
    private String sheet;

    @Column(name = "row_number")
    private Integer rowNumber;

    @Column(length = 20)
    private String cell;

    private Integer paragraph;

    @Column(columnDefinition = "TEXT")
    private String quote;

    @Column(name = "quote_verified", nullable = false)
    private boolean quoteVerified;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

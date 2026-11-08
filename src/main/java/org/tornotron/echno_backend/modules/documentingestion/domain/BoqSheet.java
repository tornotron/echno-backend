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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * One sheet of a workbook read as a BOQ: the column mapping found or confirmed for it, the top rows a
 * person sees while mapping, and what reading it found. Its segment is created when a person confirms
 * the mapping, which is the class confirmation of section 6.2 for a BOQ sheet.
 */
@Entity
@Table(name = "doc_ingest_boq_sheet")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class BoqSheet implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "segment_id")
    private UUID segmentId;

    @Column(name = "sheet_name", nullable = false, length = 100)
    private String sheetName;

    @Column(name = "sheet_index", nullable = false)
    private int sheetIndex;

    @Column(name = "hidden", nullable = false)
    private boolean hidden;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BoqSheetStatus status;

    @Column(name = "header_row")
    private Integer headerRow;

    /** Field key to 1-based column. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_map", columnDefinition = "jsonb", nullable = false)
    private Map<String, Integer> columns = new LinkedHashMap<>();

    @Column(name = "code_prefix", length = 20)
    private String codePrefix;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preview", columnDefinition = "jsonb", nullable = false)
    private List<BoqPreviewRow> preview = new ArrayList<>();

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "jsonb")
    private BoqSheetSummary summary;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "extracted_at")
    private LocalDateTime extractedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

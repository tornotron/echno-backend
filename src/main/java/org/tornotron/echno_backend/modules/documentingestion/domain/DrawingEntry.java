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
 * One row of the drawing register (design section 4.3): a drawing number at one revision, its title-block
 * values as printed, and where the sheet is. The rows of one number in a package form a revision chain;
 * the newest read revision is current, and older ones are kept with a link to the one that replaced them.
 * Written only by the apply step, with provenance on each field. No core table holds drawings.
 */
@Entity
@Table(name = "doc_ingest_drawing")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class DrawingEntry implements TenantScopedEntity {

    /** {@code READ}: the title block was read. {@code NOT_READ}: a DWG, named by its file only. */
    public enum Status {
        READ,
        NOT_READ
    }

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

    @Column(name = "drawing_number", nullable = false, length = 100)
    private String drawingNumber;

    /** The number as compared: upper case, no spaces. */
    @Column(name = "number_key", nullable = false, length = 100)
    private String numberKey;

    @Column(name = "revision", length = 20)
    private String revision;

    /** The revision as compared, empty when none was found. */
    @Column(name = "revision_key", nullable = false, length = 20)
    private String revisionKey;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "discipline", length = 60)
    private String discipline;

    /** The revision's date as printed. */
    @Column(name = "revision_date_text", length = 60)
    private String revisionDateText;

    /** The same date when it reads plainly as one. */
    @Column(name = "revision_date")
    private LocalDate revisionDate;

    @Column(name = "scale", length = 60)
    private String scale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "revision_history", columnDefinition = "jsonb")
    private List<Map<String, Object>> revisionHistory;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    /** The older revision this one replaced. */
    @Column(name = "supersedes_id")
    private UUID supersedesId;

    /** The newer revision that replaced this one. */
    @Column(name = "superseded_by_id")
    private UUID supersededById;

    /** {@code PDF}, {@code DXF} or {@code DWG}. */
    @Column(name = "source_format", nullable = false, length = 10)
    private String sourceFormat;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "sheet_page")
    private Integer sheetPage;

    @Column(name = "layout", length = 255)
    private String layout;

    /** The title-block crop's key relative to the document's output prefix, or null. */
    @Column(name = "crop_image", length = 100)
    private String cropImage;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "updated_by")
    private Long updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

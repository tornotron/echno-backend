package org.tornotron.echno_backend.modules.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One chunk of an ingested document, with the embedding that makes it findable by meaning.
 *
 * <p>Tenant scoped behind {@code orgFilter} like every module entity. The attachment and the
 * project are plain ids with foreign keys in the changelog, so deleting the attachment removes its
 * chunks (the database cascades) and the module reads nothing of the core's it does not need.
 * {@code projectId} is null for an organisation-wide document: the schema supports it, but the
 * similarity query matches {@code project_id IN (...)}, which never matches null, so such rows are
 * not reachable until a later release chooses to expose them.
 *
 * <p>The vector dimension is fixed per corpus ({@value #EMBEDDING_DIMENSION}, the spec's default);
 * changing it is a new migration and a re-index of every row, never a configuration toggle.
 */
@Entity
@Table(name = "assistant_document_unit")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class AssistantDocumentUnit implements TenantScopedEntity {

    public static final int EMBEDDING_DIMENSION = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "page_no")
    private Integer pageNo;

    @Column(name = "char_start")
    private Integer charStart;

    @Column(name = "char_end")
    private Integer charEnd;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // Lets a re-ingest skip a chunk whose text has not changed instead of embedding it again.
    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSION)
    @Column(nullable = false)
    private float[] embedding;

    // The model and version that produced the vector, so a model change is an explicit re-index.
    @Column(name = "embedding_model", nullable = false, length = 200)
    private String embeddingModel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

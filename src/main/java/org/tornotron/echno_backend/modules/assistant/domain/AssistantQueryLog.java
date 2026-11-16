package org.tornotron.echno_backend.modules.assistant.domain;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * One row per question asked, written whether the answer was an answer, a clarification, a
 * decline or a failure: the record of what the assistant was asked, what it looked at, which
 * model said what, and what it cost.
 *
 * <p>Tenant scoped behind {@code orgFilter} like every module entity, so one organization's log
 * is invisible to another's. {@code userId} is a plain id with no foreign key, so the history
 * outlives the person who asked; {@code projectId} is likewise plain, so a deleted project does
 * not take its audit rows with it.
 */
@Entity
@Table(name = "assistant_query_log")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class AssistantQueryLog implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "question", nullable = false, columnDefinition = "text")
    private String question;

    /** The project and period the question was answered for, as resolved. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolved_scope", columnDefinition = "jsonb")
    private Map<String, Object> resolvedScope;

    @Column(name = "providers_selected", columnDefinition = "text[]")
    private List<String> providersSelected;

    /** Ids of the evidence the answer was written from. */
    @Column(name = "evidence_ids", columnDefinition = "text[]")
    private List<String> evidenceIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private AskOutcome outcome;

    /** The statements, the clarification or the decline, as shown to the person. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer", columnDefinition = "jsonb")
    private Map<String, Object> answer;

    @Column(name = "llm_model", length = 200)
    private String llmModel;

    @Column(name = "llm_model_version", length = 200)
    private String llmModelVersion;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "embedding_tokens", nullable = false)
    private int embeddingTokens;

    @Column(name = "estimated_cost_micros", nullable = false)
    private long estimatedCostMicros;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

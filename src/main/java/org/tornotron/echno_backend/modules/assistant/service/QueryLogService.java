package org.tornotron.echno_backend.modules.assistant.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;
import org.tornotron.echno_backend.modules.assistant.domain.AskOutcome;
import org.tornotron.echno_backend.modules.assistant.domain.AssistantQueryLog;
import org.tornotron.echno_backend.modules.assistant.dto.QueryLogDto;
import org.tornotron.echno_backend.modules.assistant.mapper.AssistantQueryLogMapper;
import org.tornotron.echno_backend.modules.assistant.pipeline.ScopeResolver.PreviousScope;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantQueryLogRepository;
import org.tornotron.echno_backend.modules.assistant.time.AssistantClock;

/**
 * The audit log's reads and its one write. Every method is {@code @Transactional}, which is what
 * turns the tenant filter on for the repository calls under it: the repository's own proxy is not
 * one of ours, so a read made outside a transaction would see every organization's rows.
 */
@Service
@AssistantModuleEnabled
public class QueryLogService {

    /** Everything one question leaves behind in the log. */
    public record Entry(Long projectId, Long userId, String question, Map<String, Object> resolvedScope,
                        List<String> providersSelected, List<String> evidenceIds, AskOutcome outcome,
                        Map<String, Object> answer, String llmModel, String llmModelVersion,
                        int promptTokens, int completionTokens, int embeddingTokens, long estimatedCostMicros, long latencyMs) {
        public Entry(Long projectId, Long userId, String question, Map<String, Object> resolvedScope,
                     List<String> providersSelected, List<String> evidenceIds, AskOutcome outcome,
                     Map<String, Object> answer, String llmModel, String llmModelVersion,
                     int promptTokens, int completionTokens, long estimatedCostMicros, long latencyMs) {
            this(projectId, userId, question, resolvedScope, providersSelected, evidenceIds, outcome, answer,
                    llmModel, llmModelVersion, promptTokens, completionTokens, 0, estimatedCostMicros, latencyMs);
        }
    }

    private final AssistantQueryLogRepository repository;
    private final AssistantQueryLogMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final AssistantProperties props;
    private final Clock clock;

    public QueryLogService(AssistantQueryLogRepository repository, AssistantQueryLogMapper mapper,
                           TenantEntityHelper tenantEntityHelper, AssistantProperties props,
                           @AssistantClock Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public UUID record(Entry entry) {
        AssistantQueryLog row = new AssistantQueryLog();
        row.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        row.setProjectId(entry.projectId());
        row.setUserId(entry.userId());
        row.setQuestion(entry.question());
        row.setResolvedScope(entry.resolvedScope());
        row.setProvidersSelected(entry.providersSelected());
        row.setEvidenceIds(entry.evidenceIds());
        row.setOutcome(entry.outcome());
        row.setAnswer(entry.answer());
        row.setLlmModel(entry.llmModel());
        row.setLlmModelVersion(entry.llmModelVersion());
        row.setPromptTokens(entry.promptTokens());
        row.setCompletionTokens(entry.completionTokens());
        row.setEmbeddingTokens(entry.embeddingTokens());
        row.setEstimatedCostMicros(entry.estimatedCostMicros());
        row.setLatencyMs(entry.latencyMs());
        return repository.save(row).getId();
    }

    /**
     * The project of the caller's own previous question, if it is still inside the follow-up
     * window. A row that is somebody else's, in another organization, too old, or without a project
     * is no scope at all: the follow-up leans on nothing else.
     */
    @Transactional(readOnly = true)
    public Optional<PreviousScope> previousScope(UUID previousQueryId, Long userId) {
        Duration window = Duration.ofMinutes(props.getFollowupWindowMinutes());
        return repository.findByIdAndUserId(previousQueryId, userId)
                .filter(row -> row.getProjectId() != null && row.getCreatedAt() != null)
                .filter(row -> Duration.between(row.getCreatedAt(), Instant.now(clock)).compareTo(window) < 0)
                .map(row -> new PreviousScope(row.getProjectId(),
                        String.valueOf(row.getResolvedScope() == null ? row.getProjectId() : row.getResolvedScope().get("projectName"))));
    }

    /** The caller's own questions, newest first. */
    @Transactional(readOnly = true)
    public Page<QueryLogDto> mine(Long userId, int pageNo, int pageSize) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(pageNo, pageSize)).map(mapper::toDto);
    }

    /** Every question in the organization, newest first. */
    @Transactional(readOnly = true)
    public Page<QueryLogDto> all(int pageNo, int pageSize) {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(pageNo, pageSize)).map(mapper::toDto);
    }
}

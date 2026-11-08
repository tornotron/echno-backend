package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.llm.ChatCompletionResult;
import org.tornotron.echno_backend.common.llm.LlmEndpointProperties;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.LlmCall;
import org.tornotron.echno_backend.modules.documentingestion.domain.LlmCallOutcome;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.repository.ExtractJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.LlmCallRepository;

/**
 * Writes what one call left behind. Callers run each method inside {@code TransactionRetryTemplate},
 * so an answer's candidates, its additions to the job's result and its ledger row land together: a
 * chunk is either read with its {@code OK} row, or not read at all and sent again.
 */
@Component
public class ChunkRecorder {

    private final LlmCallRepository ledger;
    private final ExtractJobRepository jobs;
    private final CandidateWriter candidates;
    private final LlmBudget budget;
    private final LlmEndpointProperties endpoint;
    private final TenantEntityHelper tenantEntityHelper;

    public ChunkRecorder(LlmCallRepository ledger, ExtractJobRepository jobs, CandidateWriter candidates,
                         LlmBudget budget, LlmEndpointProperties endpoint, TenantEntityHelper tenantEntityHelper) {
        this.ledger = ledger;
        this.jobs = jobs;
        this.candidates = candidates;
        this.budget = budget;
        this.endpoint = endpoint;
        this.tenantEntityHelper = tenantEntityHelper;
    }

    /** A read answer: its candidates upserted on job, chunk and natural key, its result merged, and its OK row. */
    public void answered(ExtractContext context, Extractor extractor, ChunkPages chunk, ChatCompletionResult result,
                         ChunkOutput output) {
        for (ProposedCandidate c : output.candidates()) {
            candidates.upsertFromChunk(context.jobId(), chunk.key(), c);
        }
        if (!output.result().isEmpty()) {
            ExtractJob job = jobs.findByIdScoped(context.jobId()).orElseThrow();
            job.setResult(merge(job.getResult(), output.result()));
            jobs.save(job);
        }
        call(context, extractor, chunk, result, LlmCallOutcome.OK);
    }

    /** One call that did not give a read chunk, with how it ended. */
    public void call(ExtractContext context, Extractor extractor, ChunkPages chunk, ChatCompletionResult result,
                     LlmCallOutcome outcome) {
        LlmCall row = new LlmCall();
        row.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        row.setPackageId(context.packageId());
        row.setDocumentId(context.documentId());
        row.setJobId(context.jobId());
        row.setExtractorKey(extractor.key());
        row.setChunkKey(chunk.key());
        row.setCallNumber(ledger.lastCallNumber(context.organizationId(), context.jobId(), chunk.key()) + 1);
        row.setPageFrom(chunk.first());
        row.setPageTo(chunk.last());
        row.setPages(chunk.describe());
        row.setModel(cut(result.model() != null ? result.model() : endpoint.getModel(), 120));
        row.setModelVersion(cut(endpoint.getModelVersion() == null || endpoint.getModelVersion().isBlank()
                ? null : endpoint.getModelVersion(), 120));
        row.setPromptVersion(extractor.prompt().version());
        row.setPromptTokens(result.promptTokens());
        row.setCompletionTokens(result.completionTokens());
        row.setEstimatedCostUsd(budget.cost(result.promptTokens(), result.completionTokens()));
        row.setFinishReason(cut(result.finishReason(), 40));
        row.setLatencyMs(result.latency() == null ? 0 : result.latency().toMillis());
        row.setHttpStatus(result.httpStatus());
        row.setOutcome(outcome);
        row.setCalledAt(LocalDateTime.now());
        ledger.save(row);
    }

    static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        patch.forEach((k, v) -> {
            if (v instanceof Map<?, ?> pm && out.get(k) instanceof Map<?, ?> bm) {
                Map<String, Object> nested = new LinkedHashMap<>();
                bm.forEach((bk, bv) -> nested.put(String.valueOf(bk), bv));
                pm.forEach((pk, pv) -> nested.put(String.valueOf(pk), pv));
                out.put(k, nested);
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

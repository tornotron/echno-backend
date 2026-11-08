package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.List;
import java.util.Map;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;

/**
 * What one chunk's answer gave, after the quote check: candidates to upsert on job, chunk and natural
 * key, and keys to merge into the job's result. Both land in the same transaction as the ledger row.
 */
public record ChunkOutput(List<ProposedCandidate> candidates, Map<String, Object> result) {

    public ChunkOutput {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        result = result == null ? Map.of() : Map.copyOf(result);
    }
}

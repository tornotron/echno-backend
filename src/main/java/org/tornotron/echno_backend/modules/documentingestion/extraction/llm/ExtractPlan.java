package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.List;
import java.util.Map;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;

/**
 * What a job has to read through the LLM, after the deterministic part.
 *
 * @param toSend Pages to send, in order; empty when nothing needs the LLM
 * @param unread Pages that will not be sent, with why: not in English, or not parsed
 * @param result Merged into the job's result before any chunk runs
 */
public record ExtractPlan(List<PageText> toSend, List<UnreadPage> unread, Map<String, Object> result) {

    public ExtractPlan {
        toSend = toSend == null ? List.of() : List.copyOf(toSend);
        unread = unread == null ? List.of() : List.copyOf(unread);
        result = result == null ? Map.of() : Map.copyOf(result);
    }

    public static ExtractPlan nothingToSend(Map<String, Object> result) {
        return new ExtractPlan(List.of(), List.of(), result);
    }
}

package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.util.List;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;

/**
 * How a job's chunks ended, for {@link Extractor#complete}.
 *
 * @param unread Pages not read: those never sent and those whose chunk failed after its retries
 * @param usedLlm Whether any chunk was sent
 */
public record ExtractCompletion(List<UnreadPage> unread, boolean usedLlm) {

    public ExtractCompletion {
        unread = unread == null ? List.of() : List.copyOf(unread);
    }
}

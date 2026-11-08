package org.tornotron.echno_backend.modules.documentingestion.extraction;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;

/**
 * What an extractor hands the writer: one value of one kind, with its sources and the checks it
 * failed. {@code supersedesCandidateId} links a revised value to the one it replaces.
 */
public record ProposedCandidate(UUID packageId, UUID documentId, UUID segmentId, CandidateKind kind, String naturalKey,
                                Map<String, Object> payload, List<CandidateFlag> flags, ExtractionMethod method,
                                List<ProposedSource> sources, UUID supersedesCandidateId) {

    /**
     * Where one payload field came from.
     *
     * @param field The payload field this source supports
     * @param box Normalized {@code [x0, y0, x1, y1]}, or null
     */
    public record ProposedSource(String field, UUID documentId, String documentSha256, Integer page, List<Double> box,
                                 String sheet, Integer rowNumber, String cell, Integer paragraph, String quote,
                                 boolean quoteVerified) {
    }
}

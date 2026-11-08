package org.tornotron.echno_backend.modules.documentingestion.extraction.quote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;

/**
 * Turns the values a model gave into a candidate, through the quote check: the one path every LLM
 * extractor takes to propose a value. A value whose numbers or dates are not in its quote is dropped
 * and logged; a value whose page or quote fails keeps {@code quote_verified = false} and the
 * {@value #QUOTE_NOT_FOUND} flag, so it is never accepted in bulk; a value from an OCR page is flagged
 * so a reviewer checks it against the page image.
 */
@Slf4j
public final class QuotedCandidates {

    public static final String QUOTE_NOT_FOUND = "QUOTE_NOT_FOUND";
    public static final String OCR_PAGE = "OCR_PAGE";

    private QuotedCandidates() {
    }

    /**
     * One field as the model gave it.
     *
     * @param page  The page the model cited
     * @param quote The model's verbatim quote
     */
    public record LlmValue(String field, Object value, Integer page, String quote) {
    }

    /** The candidate, or null when every value was dropped. {@code naturalKey} is required: upserts key on it. */
    public static ProposedCandidate build(ExtractContext context, ChunkPages chunk, CandidateKind kind,
                                          String naturalKey, List<LlmValue> values) {
        if (naturalKey == null || naturalKey.isBlank()) {
            throw new IllegalArgumentException("An LLM candidate needs a natural key");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        List<ProposedCandidate.ProposedSource> sources = new ArrayList<>();
        List<CandidateFlag> flags = new ArrayList<>();
        boolean ocr = false;
        for (LlmValue v : values) {
            if (v.value() == null) {
                continue;
            }
            List<String> missing = QuotedNumbers.missing(v.value(), v.quote());
            if (!missing.isEmpty()) {
                log.info("Dropped field {} of a {} candidate in job {} (page {}): {} number(s) in it are not in its quote",
                        v.field(), kind, context.jobId(), v.page(), missing.size());
                continue;
            }
            QuoteCheck.Match match = QuoteCheck.check(chunk, v.page(), v.quote());
            payload.put(v.field(), v.value());
            sources.add(new ProposedCandidate.ProposedSource(v.field(), context.documentId(), context.documentSha256(),
                    v.page(), match.box(), null, null, null, null, v.quote(), match.verified()));
            if (!match.verified() && flags.stream().noneMatch(f -> f.code().equals(QUOTE_NOT_FOUND))) {
                flags.add(new CandidateFlag(QUOTE_NOT_FOUND, match.failure()));
            }
            ocr |= match.ocr();
        }
        if (payload.isEmpty()) {
            return null;
        }
        if (ocr) {
            flags.add(new CandidateFlag(OCR_PAGE, "Read from a scanned page; check it against the page image."));
        }
        return new ProposedCandidate(context.packageId(), context.documentId(), context.segmentId(), kind, naturalKey,
                payload, flags, ocr ? ExtractionMethod.OCR_LLM : ExtractionMethod.LLM, sources, null);
    }
}

package org.tornotron.echno_backend.modules.documentingestion.dto;

import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.SourceRef;

/** Where a value came from: page and box, or sheet, row and cell, or paragraph, with the quote. */
public record SourceRefDto(UUID id, String field, UUID documentId, String documentSha256, Integer page,
                           List<Double> box, String sheet, Integer rowNumber, String cell, Integer paragraph,
                           String quote, boolean quoteVerified) {

    public static SourceRefDto of(SourceRef s) {
        return new SourceRefDto(s.getId(), s.getField(), s.getDocumentId(), s.getDocumentSha256(), s.getPage(),
                s.getBox(), s.getSheet(), s.getRowNumber(), s.getCell(), s.getParagraph(), s.getQuote(),
                s.isQuoteVerified());
    }
}

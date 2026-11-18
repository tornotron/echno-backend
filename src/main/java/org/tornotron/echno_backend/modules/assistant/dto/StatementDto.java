package org.tornotron.echno_backend.modules.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One sentence of an answer and the evidence it rests on.")
public record StatementDto(
        @Schema(description = "The sentence.") String text,
        @Schema(description = "FACT is backed by the cited evidence; INFERENCE and RECOMMENDATION are the assistant's "
                + "reading and advice, shown apart from the facts.", example = "FACT") String kind,
        @Schema(description = "Ids of the evidence units that back it; each is in the response's evidence list.") List<String> evidenceIds
) {}

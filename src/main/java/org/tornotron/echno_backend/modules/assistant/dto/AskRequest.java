package org.tornotron.echno_backend.modules.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "A question for the assistant, optionally leaning on the caller's previous one.")
public record AskRequest(
        @Schema(description = "The question, in plain words.", example = "How many people were on site at Tower B this week?")
        @NotBlank @Size(max = 1000) String question,

        @Schema(nullable = true, description = "The query id of the caller's own previous question, if this one follows on "
                + "from it and was asked less than the follow-up window ago. Only its project is reused; nothing else is remembered.")
        UUID previousQueryId
) {}

package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "A person's choice on a change whose target was edited by hand since the value was applied. "
        + "APPLY_CHANGE writes the change over the record on the next apply; KEEP_RECORD rejects the change and keeps "
        + "the record as it is.")
public record ConflictChoiceRequest(@NotNull Choice choice) {

    public enum Choice {
        APPLY_CHANGE, KEEP_RECORD
    }
}

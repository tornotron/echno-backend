package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "A note on a bill's timeline, or the reason a bill is returned for correction.")
public record BillCommentRequest(
        @NotBlank @Size(max = 4000) String text
) {}

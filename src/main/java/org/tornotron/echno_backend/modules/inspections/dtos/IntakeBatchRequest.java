package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Up to 100 findings. An item that fails validation is refused on its own; only an empty or "
        + "oversized envelope, or a body that is not readable as this shape, refuses the whole call.")
public record IntakeBatchRequest(
        @NotEmpty @Size(max = 100) List<IntakeObservationRequest> items
) {}

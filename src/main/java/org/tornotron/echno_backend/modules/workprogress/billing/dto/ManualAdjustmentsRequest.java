package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Replaces the manual adjustments of a bill not yet certified.")
public record ManualAdjustmentsRequest(
        @NotNull @Valid @Size(max = 50) List<ManualAdjustmentRequest> adjustments
) {}

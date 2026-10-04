package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Records the joint measurement of a submitted bill: who measured and when, and the measured and accepted quantities (or the certified percent of a milestone). It may be saved more than once before the bill is verified.")
public record MeasurementRequest(
        @Schema(description = "Date of the joint measurement; not in the future.", nullable = true)
        LocalDate measurementDate,
        @Schema(description = "The engineer who measured.", nullable = true)
        @Size(max = 150) String measuredBy,
        @Schema(description = "The client's representative at the measurement.", nullable = true)
        @Size(max = 150) String clientRepresentative,
        @Schema(description = "Percent of the milestone value accepted, for a milestone bill; not more than claimed.", nullable = true)
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal certifiedPercent,
        @Schema(nullable = true)
        @Valid @Size(max = 2000) List<MeasurementLineRequest> lines
) {}

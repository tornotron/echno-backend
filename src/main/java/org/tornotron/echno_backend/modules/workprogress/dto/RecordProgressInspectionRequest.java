package org.tornotron.echno_backend.modules.workprogress.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.domain.DelayReason;
import org.tornotron.echno_backend.modules.workprogress.domain.ProgressOutcome;

@Schema(description = "Records what a progress inspection found for one schedule activity. The record is final once saved "
        + "and is applied to the activity at once: progress, actual dates, forecast finish and status. Planned dates and "
        + "other activities are not changed.")
public record RecordProgressInspectionRequest(
        @Schema(description = "The leaf WBS activity inspected.", example = "42")
        @NotNull Long wbsElementId,
        @Schema(description = "The date of the inspection; not in the future.", example = "2026-09-28")
        @NotNull LocalDate inspectionDate,
        @Schema(description = "DONE, PARTIAL or NOT_DONE. A milestone takes DONE or NOT_DONE.", example = "PARTIAL")
        @NotNull ProgressOutcome outcome,
        @Schema(description = "Cumulative percent complete. Required for PARTIAL (above 0, below 100); DONE is 100 and NOT_DONE is 0.", example = "60", nullable = true)
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal percentComplete,
        @Schema(description = "When work started. Needed for DONE and PARTIAL unless the activity already has one.", example = "2026-09-10", nullable = true)
        LocalDate actualStartDate,
        @Schema(description = "When the activity finished. Needed for DONE; refused otherwise.", example = "2026-09-27", nullable = true)
        LocalDate actualFinishDate,
        @Schema(description = "The revised finish date for an unfinished activity. The planned finish is not changed.", example = "2026-10-05", nullable = true)
        LocalDate forecastFinishDate,
        @Schema(description = "Why the activity is late. Required when the activity is behind its planned finish.", example = "MATERIAL", nullable = true)
        DelayReason delayReason,
        @Schema(description = "Detail on the delay. Required when the reason is OTHER.", example = "Cement delivery held at the depot for three days", nullable = true)
        @Size(max = 2000) String delayNotes,
        @Schema(description = "The building, floor or zone where the work was inspected, in the activity's project.", nullable = true)
        UUID spatialNodeId,
        @Schema(description = "Anything else the inspector wants on the record.", nullable = true)
        @Size(max = 4000) String remarks
) {}

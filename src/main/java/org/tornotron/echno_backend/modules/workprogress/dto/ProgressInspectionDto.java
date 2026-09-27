package org.tornotron.echno_backend.modules.workprogress.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.domain.DelayReason;
import org.tornotron.echno_backend.modules.workprogress.domain.ProgressOutcome;

@Schema(description = "One progress inspection of a schedule activity, as recorded. The planned finish and the delay are the "
        + "values at the time it was recorded.")
public record ProgressInspectionDto(
        UUID id,
        Long projectId,
        Long wbsElementId,
        @Schema(description = "wbsCode of the activity inspected.", example = "1.2.3")
        String wbsCode,
        @Schema(description = "Title of the activity inspected.", example = "RCC Column Casting - Block A")
        String activityTitle,
        LocalDate inspectionDate,
        ProgressOutcome outcome,
        BigDecimal percentComplete,
        LocalDate actualStartDate,
        LocalDate actualFinishDate,
        LocalDate forecastFinishDate,
        @Schema(description = "The activity's planned finish when the inspection was recorded.")
        LocalDate plannedFinishDate,
        @Schema(description = "Days behind the planned finish when recorded; 0 when on time, null with no planned finish.")
        Integer delayDays,
        DelayReason delayReason,
        String delayNotes,
        UUID spatialNodeId,
        String remarks,
        Long inspectorEmployeeId,
        @Schema(description = "Name of the employee who recorded it.", example = "Ravi Kumar")
        String inspectorName,
        LocalDateTime createdAt
) {}

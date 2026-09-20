package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.WeekendHolidayTreatment;

@Schema(description = "What a leave request over a date range would cost under a policy.")
@Data
public class LeaveDaysCalculationResultDto {

    @Schema(description = "Days that would be charged to the balance.", example = "4.0")
    private Double totalDays;

    @Schema(description = "Calendar days from the first to the last day inclusive, less the half-day "
            + "allowances at either end. Equal to totalDays under CHARGE_ALL_DAYS.", example = "4.0")
    private Double calendarDays;

    @Schema(description = "Weekend and holiday days inside the range that the treatment left uncharged.",
            example = "0")
    private Integer nonWorkingDaysExcluded;

    @Schema(description = "The treatment applied: the policy's when leavePolicyId was given, CHARGE_ALL_DAYS "
            + "otherwise.", example = "SANDWICH")
    private WeekendHolidayTreatment deductionRule;
}

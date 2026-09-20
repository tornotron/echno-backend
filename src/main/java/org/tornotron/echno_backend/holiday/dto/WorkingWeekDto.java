package org.tornotron.echno_backend.holiday.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.DayOfWeek;
import java.util.List;

@Schema(description = "The days of the week the organization works. Monday to Friday until the "
        + "organization changes it.")
@Data
public class WorkingWeekDto {

    @Schema(description = "Id of the organization.", example = "2")
    private Long organizationId;

    @Schema(description = "The working days, in weekday order.", example = "[\"MONDAY\", \"TUESDAY\", "
            + "\"WEDNESDAY\", \"THURSDAY\", \"FRIDAY\"]")
    private List<DayOfWeek> workingDays;
}

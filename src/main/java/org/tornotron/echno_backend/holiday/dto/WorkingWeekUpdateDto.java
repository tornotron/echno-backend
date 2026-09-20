package org.tornotron.echno_backend.holiday.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.DayOfWeek;
import java.util.List;

@Schema(description = "Payload to set the organization's working days.")
@Data
public class WorkingWeekUpdateDto {

    @Schema(description = "The days the organization works. At least one day.", example = "[\"MONDAY\", "
            + "\"TUESDAY\", \"WEDNESDAY\", \"THURSDAY\", \"FRIDAY\", \"SATURDAY\"]")
    @NotEmpty(message = "workingDays must name at least one day")
    private List<@NotNull(message = "workingDays must not contain null") DayOfWeek> workingDays;
}

package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.HalfDayType;

import java.time.LocalDate;

/**
 * Payload to calculate how many leave days a date range works out to.
 *
 * <p>Replaces the {@code Map<String, String>} the endpoint pulled four keys out of by hand. The map
 * published as {@code additionalProperties}, so the document named none of them, and the hand
 * parsing was doing the two jobs a bean does better: {@code LocalDate.parse} on a key the caller
 * left out threw a NullPointerException, and a malformed date threw DateTimeParseException, both of
 * which the global handler answers with 500. The endpoint has documented 400 for exactly those two
 * cases since it was written. Reading the payload as a bean is what makes the documented answer the
 * real one, so the two required dates carry {@code @NotNull} and the parse itself now happens in
 * Jackson, which is a 400 either way.
 */
@Schema(description = "Date range to work out a leave day count for, with the optional half-day "
        + "type at either end.")
@Data
public class LeaveDaysCalculationDto {

    @Schema(description = "First day of the range.", example = "2026-09-14")
    @NotNull(message = "startDate is required")
    private LocalDate startDate;

    @Schema(description = "Last day of the range.", example = "2026-09-18")
    @NotNull(message = "endDate is required")
    private LocalDate endDate;

    @Schema(description = "Which half of the first day is taken, when the range starts at midday.")
    private HalfDayType startHalfDayType;

    @Schema(description = "Which half of the last day is taken, when the range ends at midday.")
    private HalfDayType endHalfDayType;
}

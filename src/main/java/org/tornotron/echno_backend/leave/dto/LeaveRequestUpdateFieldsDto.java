package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.HalfDayType;

import java.time.LocalDate;

/**
 * The fields a partial leave-request update may carry, and the type each one is read as.
 *
 * <p>See {@code org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto} for why the endpoint
 * keeps the map at runtime and publishes this as its schema. Nothing deserializes into this class.
 *
 * <p>The endpoint accepts these only while the request is still a draft, and recalculates
 * {@code totalDays} from the dates afterwards, so the total is not among the fields a caller sets.
 *
 * <p>Its field list is kept honest by {@code PartialUpdateSchemaContractTest}, which reads the keys
 * {@code LeaveRequestService.updateRequest} actually accepts out of that method's source.
 */
@Schema(description = "Fields a partial leave-request update may change, accepted only while the "
        + "request is a draft. "
        + "Every field is optional and an absent field is left untouched. A field this schema "
        + "declares nullable is cleared by sending an explicit null; a field it does not declare "
        + "nullable refuses a null with a 400 rather than clearing. Keys not listed here are "
        + "ignored.")
@Data
public class LeaveRequestUpdateFieldsDto {

    @Schema(description = "First day of leave. Cannot be cleared: the column is NOT NULL and the "
            + "total-days figure is recomputed from both dates after every update, so a null is "
            + "refused with a 400 rather than applied.", example = "2026-09-14")
    private LocalDate startDate;

    @Schema(nullable = true, description = "Which half of the first day is taken, when the leave starts at midday.")
    private HalfDayType startHalfDayType;

    @Schema(description = "Last day of leave. Cannot be cleared: the column is NOT NULL and the "
            + "total-days figure is recomputed from both dates after every update, so a null is "
            + "refused with a 400 rather than applied.", example = "2026-09-18")
    private LocalDate endDate;

    @Schema(nullable = true, description = "Which half of the last day is taken, when the leave ends at midday.")
    private HalfDayType endHalfDayType;

    @Schema(nullable = true, description = "Reason given for the leave.", example = "Family function")
    private String reason;

    @Schema(nullable = true, description = "How to reach the employee during the leave.",
            example = "+91 99400 00000")
    private String contactDuringLeave;

    @Schema(nullable = true, description = "Id of the employee taking over the work.", example = "7")
    private Long handoverToId;

    @Schema(nullable = true, description = "Notes for the person taking over.",
            example = "Block A pour is scheduled for the 16th; drawings are in the shared folder.")
    private String handoverNotes;
}

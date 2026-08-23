package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.HalfDayType;

import java.time.LocalDate;

@Schema(description = "Payload to raise a leave request against a leave policy, optionally submitting it "
        + "for approval immediately.")
@Data
public class LeaveRequestCreationDto {

    @Schema(description = "Id of the leave policy the request is raised under.", example = "3")
    @NotNull(message = "Leave policy ID is required")
    private Long leavePolicyId;

    @Schema(description = "First day of leave.", example = "2026-09-14")
    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @Schema(description = "Whether the start date is a full day or a half day.", example = "FULL_DAY")
    private HalfDayType startHalfDayType;

    @Schema(description = "Last day of leave.", example = "2026-09-16")
    @NotNull(message = "End date is required")
    private LocalDate endDate;

    @Schema(description = "Whether the end date is a full day or a half day.", example = "SECOND_HALF")
    private HalfDayType endHalfDayType;

    @Schema(description = "Reason for the leave.", example = "Attending sister's wedding in Coimbatore")
    @NotBlank(message = "Reason is required")
    @Size(max = 1000, message = "Reason must not exceed 1000 characters")
    private String reason;

    @Schema(description = "Contact number or address reachable during the leave.", example = "9847012345")
    @Size(max = 100, message = "Contact during leave must not exceed 100 characters")
    private String contactDuringLeave;

    @Schema(description = "Id of the employee handling handover during the leave, if any.", example = "12")
    private Long handoverToId;

    @Schema(description = "Notes for the person receiving the handover.", example = "Site inspection at "
            + "Asset Homes Chennai is due on 2026-09-15, please coordinate with the QA team")
    @Size(max = 500, message = "Handover notes must not exceed 500 characters")
    private String handoverNotes;

    @Schema(description = "When true, submits the request for approval immediately instead of saving it "
            + "as a draft.", example = "true")
    private Boolean submitImmediately = false;
}

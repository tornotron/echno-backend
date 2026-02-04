package org.tornotron.echno_backend.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.HalfDayType;

import java.time.LocalDate;

@Data
public class LeaveRequestCreationDto {

    @NotNull(message = "Employee ID is required")
    private Long employeeId;

    @NotNull(message = "Leave policy ID is required")
    private Long leavePolicyId;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    private HalfDayType startHalfDayType;

    @NotNull(message = "End date is required")
    private LocalDate endDate;

    private HalfDayType endHalfDayType;

    @NotBlank(message = "Reason is required")
    @Size(max = 1000, message = "Reason must not exceed 1000 characters")
    private String reason;

    @Size(max = 100, message = "Contact during leave must not exceed 100 characters")
    private String contactDuringLeave;

    private Long handoverToId;

    @Size(max = 500, message = "Handover notes must not exceed 500 characters")
    private String handoverNotes;

    private Boolean submitImmediately = false;
}

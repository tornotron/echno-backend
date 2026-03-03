package org.tornotron.echno_backend.attendance.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceApprovalDto {

    @NotNull
    private ApprovalStatus approvalStatus;

    private String remarks;
}

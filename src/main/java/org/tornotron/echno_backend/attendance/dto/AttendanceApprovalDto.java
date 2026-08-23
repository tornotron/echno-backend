package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;

@Schema(description = "Payload to approve or reject an attendance record.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceApprovalDto {

    @Schema(description = "Decision on the attendance record.", example = "APPROVED")
    @NotNull
    private ApprovalStatus approvalStatus;

    @Schema(description = "Remarks explaining the decision, especially when rejecting.", example = "Confirmed with site supervisor, checked in late due to traffic")
    private String remarks;
}

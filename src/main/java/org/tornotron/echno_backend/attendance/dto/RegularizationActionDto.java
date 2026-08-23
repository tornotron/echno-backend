package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;

@Schema(description = "Payload to approve or reject a regularization request.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegularizationActionDto {

    @Schema(description = "Decision on the regularization request.", example = "APPROVED")
    @NotNull
    private RegularizationStatus status;

    @Schema(description = "Reason given when the request is rejected.", example = "No corroborating movement record for the claimed hours")
    private String rejectionReason;
}

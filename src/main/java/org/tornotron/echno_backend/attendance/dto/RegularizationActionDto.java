package org.tornotron.echno_backend.attendance.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegularizationActionDto {

    @NotNull
    private RegularizationStatus status;

    private String rejectionReason;
}

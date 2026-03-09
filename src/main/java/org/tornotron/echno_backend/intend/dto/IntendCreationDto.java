package org.tornotron.echno_backend.intend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IntendCreationDto {

    @NotBlank(message = "intendNumber is required(type: String)")
    @Size(min = 1, max = 50, message = "intendNumber must be between 1 and 50 characters")
    private String intendNumber;

    @NotNull(message = "createdBy is required(type: Long)")
    private Long createdByEmployeeId;

    @NotBlank(message = "status is required(type: String)")
    private String status;

    private LocalDateTime expectedOn;

    private String remarks;
}

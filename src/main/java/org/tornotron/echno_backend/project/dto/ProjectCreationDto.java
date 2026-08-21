package org.tornotron.echno_backend.project.dto;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProjectCreationDto {

    @NotBlank(message = "projectName is required")
    @Size(min = 3,max = 50,message = "projectName must be between 3 and 50 characters")
    private String projectName;

    @NotBlank(message = "projectAddress is required")
    @Size(min = 3,max = 50,message = "projectAddress must be between 3 and 50 characters")
    private String projectAddress;

    private LocalDateTime createdAt;

    @NotBlank(message = "status is required")
    @Size(min = 3,max = 50,message = "status must be between 3 and 50 characters")
    @Enumerated(EnumType.STRING)
    private String status;

    /** Optional construction category (e.g. RESIDENTIAL, COMMERCIAL). Drives compliance matching. */
    @Size(max = 50, message = "projectType must be at most 50 characters")
    private String projectType;

    @NotNull
    private Float projectLatitude;

    @NotNull
    private Float projectLongitude;

    private LocalDateTime startDate;

    private LocalDateTime endDate;
}

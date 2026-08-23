package org.tornotron.echno_backend.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Payload to create a construction project. Sent as the JSON data part of the "
        + "multipart create request.")
@Data
public class ProjectCreationDto {

    @Schema(description = "Project name.", example = "Tower B, Riverside Residences")
    @NotBlank(message = "projectName is required")
    @Size(min = 3,max = 50,message = "projectName must be between 3 and 50 characters")
    private String projectName;

    @Schema(description = "Site address of the project.", example = "12 Marina Road, Chennai")
    @NotBlank(message = "projectAddress is required")
    @Size(min = 3,max = 50,message = "projectAddress must be between 3 and 50 characters")
    private String projectAddress;

    @Schema(description = "Creation timestamp, set server side when omitted.", example = "2026-08-01T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Initial project status.", example = "IN_PROGRESS")
    @NotBlank(message = "status is required")
    @Size(min = 3,max = 50,message = "status must be between 3 and 50 characters")
    @Enumerated(EnumType.STRING)
    private String status;

    /** Optional construction category (e.g. RESIDENTIAL, COMMERCIAL). Drives compliance matching. */
    @Schema(description = "Optional construction category. Drives compliance matching.", example = "RESIDENTIAL")
    @Size(max = 50, message = "projectType must be at most 50 characters")
    private String projectType;

    @Schema(description = "Site latitude in decimal degrees.", example = "13.0827")
    @NotNull
    private Float projectLatitude;

    @Schema(description = "Site longitude in decimal degrees.", example = "80.2707")
    @NotNull
    private Float projectLongitude;

    @Schema(description = "Planned start date of the project.", example = "2026-09-01T00:00:00")
    private LocalDateTime startDate;

    @Schema(description = "Planned completion date of the project.", example = "2027-06-30T00:00:00")
    private LocalDateTime endDate;
}

package org.tornotron.echno_backend.project.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Payload to create a construction project. Sent as the JSON data part of the "
        + "multipart create request.")
@Data
public class ProjectCreationDto {

    /**
     * The longest description the API accepts. The column behind it is TEXT, so this is a policy
     * bound rather than a storage one and can be raised without a migration. Read by
     * {@code ProjectService} so the partial-update path holds the same line, and named on
     * {@code ProjectUpdateFieldsDto} so the published schema says so.
     */
    public static final int MAX_DESCRIPTION_LENGTH = 2000;

    @Schema(description = "Project name.", example = "Tower B, Riverside Residences")
    @NotBlank(message = "projectName is required")
    @Size(min = 3,max = 50,message = "projectName must be between 3 and 50 characters")
    private String projectName;

    @Schema(description = "Optional free-text description of the project.",
            example = "Twelve-storey residential tower, two basement levels, handover Q2 2027.")
    @Size(max = MAX_DESCRIPTION_LENGTH, message = "description must be at most 2000 characters")
    private String description;

    @Schema(description = "Street address of the site, as one line.", example = "12 Marina Road, Mylapore")
    @NotBlank(message = "projectAddress is required")
    @Size(min = 3,max = 255,message = "projectAddress must be between 3 and 255 characters")
    private String projectAddress;

    /** Optional town or city. Display only. */
    @Schema(description = "Optional town or city the site is in.", example = "Chennai")
    @Size(max = 100, message = "projectCity must be at most 100 characters")
    private String projectCity;

    /**
     * Optional Indian state or union territory. Read by compliance generation, which keys its
     * rules by state; when it is absent the state is scraped out of the free-text address
     * instead, which only works if the address happens to name one.
     */
    @Schema(description = "Optional Indian state or union territory the site is in. Used to match "
            + "statutory compliances; falls back to reading the state out of the address when absent.",
            example = "Tamil Nadu")
    @Size(max = 100, message = "projectState must be at most 100 characters")
    private String projectState;

    /** Optional postal (PIN) code. Display only. */
    @Schema(description = "Optional postal (PIN) code of the site.", example = "600004")
    @Size(max = 16, message = "projectPostalCode must be at most 16 characters")
    private String projectPostalCode;

    @Schema(description = "Creation timestamp, set server side when omitted.", example = "2026-08-01T09:00:00")
    private LocalDateTime createdAt;

    /**
     * The state the project starts in. Optional, and {@code approved} is refused: approval draws
     * up the project's compliance inspections, so it is a transition rather than a starting
     * value. See {@code ProjectService.addProject}.
     */
    @Schema(description = "State the project starts in. Optional, and upcoming when left out. "
            + "Every value except approved is accepted. A project is approved through "
            + "PATCH /projects/{id}, which is what checks that its state is known and draws up its "
            + "compliance inspections; approving it on the create form would skip both, and nothing "
            + "later can put that right because the project is already approved by then.",
            example = "upcoming",
            allowableValues = {"open", "closed", "upcoming", "completed", "dropped", "onHold", "cancelled"})
    private ProjectCreationStatus status;

    /** Optional construction category (e.g. RESIDENTIAL, COMMERCIAL). Drives compliance matching. */
    @Schema(description = "Optional construction category. Drives compliance matching.", example = "RESIDENTIAL")
    @Size(max = 50, message = "projectType must be at most 50 characters")
    private String projectType;

    /** Optional finance customer the project is billed to. Must already exist in the tenant. */
    @Schema(description = "Optional finance customer the project is billed to (its client).",
            example = "6b1e9c22-9f8a-4a1b-9c0e-1d2f3a4b5c6d")
    private UUID customerId;

    /**
     * Optional site coordinates. They were required, which contradicted the web form offering
     * them as optional and made a project with only a typed address impossible to create. Left
     * null they are simply not recorded; the range check catches a transposed or mistyped pair.
     */
    @Schema(description = "Optional site latitude in decimal degrees.", example = "13.0827")
    @DecimalMin(value = "-90", message = "projectLatitude must be between -90 and 90")
    @DecimalMax(value = "90", message = "projectLatitude must be between -90 and 90")
    private Float projectLatitude;

    @Schema(description = "Optional site longitude in decimal degrees.", example = "80.2707")
    @DecimalMin(value = "-180", message = "projectLongitude must be between -180 and 180")
    @DecimalMax(value = "180", message = "projectLongitude must be between -180 and 180")
    private Float projectLongitude;

    @Schema(description = "Planned start date of the project.", example = "2026-09-01T00:00:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    private LocalDateTime startDate;

    @Schema(description = "Planned completion date of the project.", example = "2027-06-30T00:00:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    private LocalDateTime endDate;
}

package org.tornotron.echno_backend.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "A project as it appears in a list: every scalar field of the full project "
        + "view, without the team, the tasks or the attachments. Progress still means what it "
        + "means on the full view, the mean of the project's task progress, but it is read for "
        + "the whole page in one aggregate rather than by loading each project's tasks.")
@Data
public class ProjectSummaryDto {

    @Schema(description = "Unique project id.", example = "42")
    private Long id;

    @Schema(description = "Project name.", example = "Tower B, Riverside Residences")
    private String projectName;

    @Schema(description = "Street address of the site, as one line.", example = "12 Marina Road, Mylapore")
    private String projectAddress;

    @Schema(description = "Town or city the site is in. Null when not recorded.", example = "Chennai")
    private String projectCity;

    @Schema(description = "Indian state or union territory the site is in, used to match statutory "
            + "compliances. Null when not recorded.", example = "Tamil Nadu")
    private String projectState;

    @Schema(description = "Postal (PIN) code of the site. Null when not recorded.", example = "600004")
    private String projectPostalCode;

    @Schema(description = "Creation timestamp.", example = "2026-08-01T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Id of the user who created the project. Null on projects created before "
            + "this was recorded.", example = "17")
    private Long createdBy;

    @Schema(description = "When the project was last written. Null on projects not written since "
            + "this was recorded.", example = "2026-08-28T16:41:12")
    private LocalDateTime updatedAt;

    @Schema(description = "Id of the user who last wrote the project.", example = "17")
    private Long updatedBy;

    @Schema(description = "Current project status.", example = "IN_PROGRESS")
    private ProjectCreationStatus status;

    @Schema(description = "Construction category of the project.", example = "RESIDENTIAL")
    private ProjectType projectType;

    @Schema(description = "Finance customer the project is billed to. Null when no client is set.",
            example = "6b1e9c22-9f8a-4a1b-9c0e-1d2f3a4b5c6d")
    private UUID customerId;

    @Schema(description = "Site latitude in decimal degrees.", example = "13.0827")
    private Float projectLatitude;

    @Schema(description = "Site longitude in decimal degrees.", example = "80.2707")
    private Float projectLongitude;

    @Schema(description = "Planned start date of the project.", example = "2026-09-01T00:00:00")
    private LocalDateTime startDate;

    @Schema(description = "Planned completion date of the project.", example = "2027-06-30T00:00:00")
    private LocalDateTime endDate;

    @Schema(description = "Overall completion of the project, as a percentage from 0 to 100. The "
            + "same figure the full project view reports.", example = "35.0")
    private Double progress;
}

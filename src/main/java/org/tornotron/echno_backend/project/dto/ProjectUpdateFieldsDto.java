package org.tornotron.echno_backend.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The fields a partial project update may carry, and the type each one is read as.
 *
 * <p>See {@code org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto} for why the endpoint
 * keeps the map at runtime and publishes this as its schema. Nothing deserializes into this class.
 *
 * <p>Its field list is kept honest by {@code PartialUpdateSchemaContractTest}, which reads the keys
 * {@code ProjectService.partialUpdateAProject} actually accepts out of that method's source.
 */
@Schema(description = "Fields a partial project update may change. Every field is optional; only "
        + "the fields present in the request are applied. Keys not listed here are ignored.")
@Data
public class ProjectUpdateFieldsDto {

    @Schema(description = "Name of the project.", example = "Marina Heights, phase 2")
    private String projectName;

    @Schema(description = "Street address of the site.", example = "12 Kamaraj Salai")
    private String projectAddress;

    @Schema(description = "City the site is in. Trimmed, and cleared when blank.", example = "Chennai")
    private String projectCity;

    @Schema(description = "State the site is in. Recorded in the canonical spelling for the state.",
            example = "Tamil Nadu")
    private String projectState;

    @Schema(description = "Postal code of the site. Trimmed, and cleared when blank.",
            example = "600113")
    private String projectPostalCode;

    @Schema(description = "Lifecycle status of the project.")
    private ProjectCreationStatus status;

    @Schema(description = "Planned start of the project.", example = "2026-09-01T09:00:00")
    private LocalDateTime startDate;

    @Schema(description = "Planned end of the project.", example = "2027-03-31T18:00:00")
    private LocalDateTime endDate;

    @Schema(description = "Kind of work the project covers.")
    private ProjectType projectType;

    @Schema(description = "Id of the finance customer the project bills to, as a UUID string. The "
            + "customer must belong to the caller's organization; null or blank clears the link.",
            example = "3f2a1c64-7b21-4d1e-9c8f-0a2b3c4d5e6f")
    private UUID customerId;

    @Schema(description = "Site longitude, between -180 and 180.", example = "80.2412")
    private Float projectLongitude;

    @Schema(description = "Site latitude, between -90 and 90.", example = "12.9915")
    private Float projectLatitude;
}

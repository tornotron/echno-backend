package org.tornotron.echno_backend.issue.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;

/**
 * The fields a partial issue update may carry, and the type each one is read as.
 *
 * <p>See {@code org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto} for why the endpoint
 * keeps the map at runtime and publishes this as its schema. Nothing deserializes into this class.
 *
 * <p>Its field list is kept honest by {@code PartialUpdateSchemaContractTest}, which reads the keys
 * {@code IssueService.partialUpdateAnIssue} actually accepts out of that method's source.
 */
@Schema(description = "Fields a partial issue update may change. Every field is optional; only the "
        + "fields present in the request are applied. Keys not listed here are ignored.")
@Data
public class IssueUpdateFieldsDto {

    @Schema(description = "Short issue title.", example = "Rebar spacing off on grid C")
    private String title;

    @Schema(description = "Longer description of the issue.",
            example = "Spacing measured at 220 mm against a specified 200 mm across grid C.")
    private String description;

    @Schema(description = "Category of the issue.")
    private IssueType type;

    @Schema(description = "Lifecycle status of the issue.")
    private IssueStatus status;

    @Schema(description = "Id of the employee the issue is assigned to. The employee must belong to "
            + "the caller's organization.", example = "17")
    private Long assignedToId;
}

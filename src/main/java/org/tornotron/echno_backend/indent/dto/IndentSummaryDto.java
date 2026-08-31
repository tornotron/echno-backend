package org.tornotron.echno_backend.indent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.indent.enums.IndentStatus;

import java.time.LocalDateTime;

@Schema(description = "An indent as it appears in a list: its own fields, who raised it and how "
        + "many lines it has, without the lines themselves. The full view carries every requested "
        + "item, and every item carries a whole material with its stock figures, so a page of "
        + "indents reads the material catalogue to render a list of indent numbers.")
@Data
public class IndentSummaryDto {

    @Schema(description = "Indent id.", example = "42")
    private Long id;

    @Schema(description = "Indent number.", example = "IND-2026-0015")
    private String indentNumber;

    @Schema(description = "Timestamp the indent was created.", example = "2026-01-15T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Id of the employee who raised the indent. Null where the raiser is no "
            + "longer recorded.", example = "18", nullable = true)
    private Long createdById;

    @Schema(description = "Name of the employee who raised the indent.", example = "Ramesh Kumar")
    private String createdByName;

    @Schema(description = "Id of the project the indent is raised for.", example = "3")
    private Long projectId;

    @Schema(description = "Name of the project.", example = "Asset Homes Perumbavoor Phase 2")
    private String projectName;

    @Schema(description = "Current lifecycle status of the indent.", example = "PENDING")
    private IndentStatus status;

    @Schema(description = "Date the materials are expected on site.", example = "2026-02-05T00:00:00")
    private LocalDateTime expectedOn;

    @Schema(description = "Free-text remarks on the indent.", example = "Required before slab pour on 2026-02-08")
    private String remarks;

    @Schema(description = "How many item lines the indent has. Read for the whole page in one "
            + "aggregate; the lines themselves are on the detail view.", example = "10")
    private long itemCount;
}

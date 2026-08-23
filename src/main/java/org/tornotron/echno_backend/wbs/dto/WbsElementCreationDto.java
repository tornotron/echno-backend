package org.tornotron.echno_backend.wbs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Payload to create a WBS element under a project, optionally nested under a "
        + "parent element.")
@Data
public class WbsElementCreationDto {

    @Schema(description = "Project-unique code identifying this element's position in the WBS hierarchy.", example = "1.2.3")
    @NotBlank(message = "wbsCode is required")
    @Size(max = 50, message = "wbsCode must not exceed 50 characters")
    private String wbsCode;

    @Schema(description = "Name of the WBS element.", example = "RCC Column Casting - Block A")
    @NotBlank(message = "title is required")
    @Size(max = 255, message = "title must not exceed 255 characters")
    private String title;

    @Schema(description = "Free-text description of the scope of work covered by this element.", example = "Column casting for grid lines A1 to A6, M25 grade concrete")
    private String description;

    @Schema(description = "Id of the parent WBS element to nest this element under. Omit to create a root element.", example = "12")
    private Long parentId;

    @Schema(description = "Display order among sibling elements. Defaults to 0 when omitted.", example = "1")
    private Integer sortOrder;

    @Schema(description = "Initial status of the element. See WbsStatus for the allowed values.", example = "NOT_STARTED")
    private String status;

    @Schema(description = "Planned start date.", example = "2026-01-15")
    private LocalDate startDate;

    @Schema(description = "Planned end date.", example = "2026-02-28")
    private LocalDate endDate;

    @Schema(description = "Budgeted cost for this element, in the project's base currency.", example = "850000.00")
    private BigDecimal budgetedCost;

    @Schema(description = "Relative weight of this element among its siblings, used to roll up progress to the parent.", example = "1.5")
    private Double weight;

    @Schema(description = "Id of the employee creating this element.", example = "5")
    private Long createdBy;
}

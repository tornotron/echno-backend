package org.tornotron.echno_backend.wbs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Partial update for a WBS element. Only the fields present in the request are "
        + "changed; progress may only be set on a leaf element.")
@Data
public class WbsElementUpdateDto {

    @Schema(description = "New name of the WBS element.", example = "RCC Column Casting - Block A")
    @Size(max = 255, message = "title must not exceed 255 characters")
    private String title;

    @Schema(description = "New free-text description of the scope of work.", example = "Column casting for grid lines A1 to A6, M25 grade concrete")
    private String description;

    @Schema(description = "New status of the element. See WbsStatus for the allowed values.", example = "IN_PROGRESS")
    private String status;

    @Schema(description = "New planned start date.", example = "2026-01-15")
    private LocalDate startDate;

    @Schema(description = "New planned end date.", example = "2026-02-28")
    private LocalDate endDate;

    @Schema(description = "Actual start date recorded on site.", example = "2026-01-18")
    private LocalDate actualStartDate;

    @Schema(description = "Actual end date recorded on site.", example = "2026-03-02")
    private LocalDate actualEndDate;

    @Schema(description = "New budgeted cost for this element, in the project's base currency.", example = "850000.00")
    private BigDecimal budgetedCost;

    @Schema(description = "New relative weight of this element among its siblings.", example = "1.5")
    private Double weight;

    @Schema(description = "New display order among sibling elements.", example = "2")
    private Integer sortOrder;

    @Schema(description = "New completion percentage, from 0 to 100. Only allowed when the element is a leaf.", example = "75.0")
    private Double progress;
}

package org.tornotron.echno_backend.wbs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.wbs.enums.WbsStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A WBS element with its resolved parent, creator and, on tree responses, nested children.")
@Data
public class WbsElementDto {
    @Schema(description = "Unique id of the WBS element.", example = "42")
    private Long id;
    @Schema(description = "Project-unique code identifying this element's position in the WBS hierarchy.", example = "1.2.3")
    private String wbsCode;
    @Schema(description = "Name of the WBS element.", example = "RCC Column Casting - Block A")
    private String title;
    @Schema(description = "Free-text description of the scope of work.", example = "Column casting for grid lines A1 to A6, M25 grade concrete")
    private String description;
    @Schema(description = "Depth of this element in the WBS tree, with 0 for a root element.", example = "2")
    private Integer level;
    @Schema(description = "Display order among sibling elements.", example = "1")
    private Integer sortOrder;
    @Schema(description = "Current status of the element.", example = "IN_PROGRESS")
    private WbsStatus status;
    @Schema(description = "Planned start date.", example = "2026-01-15")
    private LocalDate startDate;
    @Schema(description = "Planned end date.", example = "2026-02-28")
    private LocalDate endDate;
    @Schema(description = "Actual start date recorded on site.", example = "2026-01-18")
    private LocalDate actualStartDate;
    @Schema(description = "Actual end date recorded on site.", example = "2026-03-02")
    private LocalDate actualEndDate;
    @Schema(description = "Budgeted cost for this element, in the project's base currency.", example = "850000.00")
    private BigDecimal budgetedCost;
    @Schema(description = "Actual cost incurred for this element, summed from its descendants if it is not a leaf.", example = "612340.50")
    private BigDecimal actualCost;
    @Schema(description = "Completion percentage, from 0 to 100, weighted from children if this element is not a leaf.", example = "62.5")
    private Double progress;
    @Schema(description = "Relative weight of this element among its siblings, used to roll up progress to the parent.", example = "1.5")
    private Double weight;
    @Schema(description = "Whether this element has no children.", example = "false")
    private Boolean isLeaf;
    @Schema(description = "Id of the project this element belongs to.", example = "7")
    private Long projectId;
    @Schema(description = "Name of the project this element belongs to.", example = "Asset Homes - Kochi Riverside Phase 2")
    private String projectName;
    @Schema(description = "Id of the parent WBS element, or null for a root element.", example = "12")
    private Long parentId;
    @Schema(description = "wbsCode of the parent WBS element, or null for a root element.", example = "1.2")
    private String parentWbsCode;
    @Schema(description = "Employee who created this element.")
    private EmployeeDto createdBy;
    @Schema(description = "Timestamp the element was created.", example = "2026-01-10T09:30:00")
    private LocalDateTime createdAt;
    @Schema(description = "Timestamp the element was last updated.", example = "2026-02-05T14:12:00")
    private LocalDateTime updatedAt;
    @Schema(description = "Child elements, present on tree responses; empty or null on other reads.")
    private List<WbsElementDto> children;
}

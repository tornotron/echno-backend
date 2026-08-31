package org.tornotron.echno_backend.wbs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.wbs.enums.WbsStatus;

import java.math.BigDecimal;

@Schema(description = "Terse view of a WBS element for flat listings, without the nested children or resolved parent/creator carried by WbsElementDto.")
@Data
public class WbsElementFlatDto {
    @Schema(description = "Unique id of the WBS element.", example = "42")
    private Long id;
    @Schema(description = "Project-unique code identifying this element's position in the WBS hierarchy.", example = "1.2.3")
    private String wbsCode;
    @Schema(description = "Name of the WBS element.", example = "RCC Column Casting - Block A")
    private String title;
    @Schema(description = "Depth of this element in the WBS tree, with 0 for a root element.", example = "2")
    private Integer level;
    @Schema(description = "Display order among sibling elements.", example = "1")
    private Integer sortOrder;
    @Schema(description = "Current status of the element.", example = "IN_PROGRESS")
    private WbsStatus status;
    @Schema(description = "Budgeted cost for this element, in the project's base currency.", example = "850000.00")
    private BigDecimal budgetedCost;
    @Schema(description = "Actual cost incurred for this element.", example = "612340.50")
    private BigDecimal actualCost;
    @Schema(description = "Completion percentage, from 0 to 100.", example = "62.5")
    private Double progress;
    @Schema(description = "Whether this element has no children.", example = "true")
    private Boolean isLeaf;
    @Schema(description = "Id of the parent WBS element, or null for a root element.", example = "12", nullable = true)
    private Long parentId;
}

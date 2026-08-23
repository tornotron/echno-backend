package org.tornotron.echno_backend.wbs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Payload to move a WBS element to a new parent, optionally renaming its wbsCode "
        + "and reordering it among its new siblings in the same call.")
@Data
public class WbsMoveDto {
    @Schema(description = "Id of the new parent WBS element. Omit to move the element to the project root. Must not be the element itself or one of its own descendants.", example = "18")
    private Long newParentId;
    @Schema(description = "New wbsCode for the element, if it should change. Must stay unique within the project.", example = "2.1.4")
    private String newWbsCode;
    @Schema(description = "New display order among the element's siblings under its new parent.", example = "3")
    private Integer newSortOrder;
}

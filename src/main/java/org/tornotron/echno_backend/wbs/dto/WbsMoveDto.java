package org.tornotron.echno_backend.wbs.dto;

import lombok.Data;

@Data
public class WbsMoveDto {
    private Long newParentId;
    private String newWbsCode;
    private Integer newSortOrder;
}

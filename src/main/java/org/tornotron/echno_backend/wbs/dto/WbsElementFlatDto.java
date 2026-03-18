package org.tornotron.echno_backend.wbs.dto;

import lombok.Data;
import org.tornotron.echno_backend.wbs.enums.WbsStatus;

import java.math.BigDecimal;

@Data
public class WbsElementFlatDto {
    private Long id;
    private String wbsCode;
    private String title;
    private Integer level;
    private Integer sortOrder;
    private WbsStatus status;
    private BigDecimal budgetedCost;
    private BigDecimal actualCost;
    private Double progress;
    private Boolean isLeaf;
    private Long parentId;
}

package org.tornotron.echno_backend.indentItem.dto;

import lombok.Data;
import org.tornotron.echno_backend.material.dto.MaterialDto;

@Data
public class IndentItemDto {
    private Long id;
    private MaterialDto material;
    private String additionalSpecifications;
    private Integer requestedQuantity;
    private Integer orderedQuantity;
    private String remarks;
    private Boolean convertedToPurchaseOrder;
    private String linkedPurchaseOrderNumber;
}

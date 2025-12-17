package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;

@Component
public class IndentItemDtoConvertor {

    public static IndentItemDto convertIndentItemToDto(IndentItem item) {
        if (item == null) {
            return null;
        }

        IndentItemDto dto = new IndentItemDto();
        dto.setId(item.getId());
        dto.setMaterial(MaterialDtoConvertor.convertToDto(item.getMaterial()));
        dto.setAdditionalSpecifications(item.getAdditionalSpecifications());
        dto.setRequestedQuantity(item.getRequestedQuantity());
        dto.setOrderedQuantity(item.getOrderedQuantity());
        dto.setRemarks(item.getRemarks());
        dto.setConvertedToPurchaseOrder(item.getConvertedToPurchaseOrder());
        dto.setLinkedPurchaseOrderNumber(item.getLinkedPurchaseOrderNumber());
        return dto;
    }
}

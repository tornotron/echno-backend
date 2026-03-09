package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;

@Component
public class IndentItemDtoConvertor {

    public static IndentItemDto convertIndentItemToDto(IndentItem item, FileStorageService fileStorageService) {
        if (item == null) {
            return null;
        }

        IndentItemDto dto = new IndentItemDto();
        dto.setId(item.getId());
        dto.setMaterial(MaterialDtoConvertor.convertToDto(item.getMaterial(), fileStorageService));
        dto.setAdditionalSpecifications(item.getAdditionalSpecifications());
        dto.setRequestedQuantity(item.getRequestedQuantity());
        dto.setOrderedQuantity(item.getOrderedQuantity());
        dto.setRemarks(item.getRemarks());
        dto.setConvertedToPurchaseOrder(item.getConvertedToPurchaseOrder());
        dto.setLinkedPurchaseOrderNumber(item.getLinkedPurchaseOrderNumber());
        return dto;
    }
}

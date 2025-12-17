package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GrnItemDto;
import org.tornotron.echno_backend.grnItem.GrnItem;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class GoodsReceivedNoteDtoConvertor {

    public static GoodsReceivedNoteDto convertToDto(GoodsReceivedNote grn) {
        if (grn == null) {
            return null;
        }

        GoodsReceivedNoteDto dto = new GoodsReceivedNoteDto();
        dto.setId(grn.getId());
        dto.setGrnNumber(grn.getGrnNumber());
        dto.setReceivedOn(grn.getReceivedOn());
        dto.setDeliveryChallanNumber(grn.getDeliveryChallanNumber());
        dto.setInvoiceNumber(grn.getInvoiceNumber());
        dto.setInvoiceAmount(grn.getInvoiceAmount());

        // Received by
        if (grn.getReceivedBy() != null) {
            dto.setReceivedBy(UserDtoConvertor.convertUserToDto(grn.getReceivedBy()));
        }

        // Vendor info
        if (grn.getVendor() != null) {
            dto.setVendorId(grn.getVendor().getId());
            dto.setVendorName(grn.getVendor().getVendorName());
        }

        // Purchase Order info (will be available after entity update)
        // Placeholder for now

        // Items
        if (grn.getItems() != null && !grn.getItems().isEmpty()) {
            List<GrnItemDto> itemDtos = grn.getItems().stream()
                    .map(GoodsReceivedNoteDtoConvertor::convertItemToDto)
                    .collect(Collectors.toList());
            dto.setItems(itemDtos);
        }

        return dto;
    }

    public static GrnItemDto convertItemToDto(GrnItem item) {
        if (item == null) {
            return null;
        }

        GrnItemDto dto = new GrnItemDto();
        dto.setId(item.getId());
        dto.setOrderedQuantity(item.getOrderedQuantity());
        dto.setReceivedQuantity(item.getReceivedQuantity());

        // Material info
        if (item.getMaterial() != null) {
            dto.setMaterialId(item.getMaterial().getId());
            dto.setMaterialName(item.getMaterial().getMaterialName());
        }

        return dto;
    }
}

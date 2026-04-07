package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GrnItemDto;
import org.tornotron.echno_backend.grnItem.GrnItem;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class GoodsReceivedNoteDtoConvertor {

    public static GoodsReceivedNoteDto convertToDto(GoodsReceivedNote grn, FileStorageService fileStorageService) {
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
            dto.setReceivedBy(EmployeeDtoConvertor.convertEmployeeToDto(grn.getReceivedBy(), fileStorageService));
        }

        // Vendor info
        if (grn.getVendor() != null) {
            dto.setVendorId(grn.getVendor().getId());
            dto.setVendorName(grn.getVendor().getVendorName());
        }

        if (grn.getPurchaseOrder() != null) {
            dto.setPurchaseOrderId(grn.getPurchaseOrder().getId());
            dto.setPurchaseOrderNumber(grn.getPurchaseOrder().getPoNumber());
        }
        // Project info
        if (grn.getProject() != null) {
            dto.setProjectId(grn.getProject().getId());
            dto.setProjectName(grn.getProject().getProjectName());
        }

        // Storage location info
        if (grn.getStorageLocation() != null) {
            dto.setStorageLocationId(grn.getStorageLocation().getId());
            dto.setStorageLocationName(grn.getStorageLocation().getLocationName());
        }

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

        dto.setUnitCost(item.getUnitCost());

        return dto;
    }
}

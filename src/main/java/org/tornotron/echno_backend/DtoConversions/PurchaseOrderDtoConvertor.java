package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderItemDto;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class PurchaseOrderDtoConvertor {

    public static PurchaseOrderDto convertToDto(PurchaseOrder purchaseOrder, FileStorageService fileStorageService) {
        if (purchaseOrder == null) {
            return null;
        }

        PurchaseOrderDto dto = new PurchaseOrderDto();
        dto.setId(purchaseOrder.getId());
        dto.setPoNumber(purchaseOrder.getPoNumber());
        dto.setStatus(purchaseOrder.getStatus());
        dto.setCreatedAt(purchaseOrder.getCreatedAt());
        dto.setExpectedDeliveryDate(purchaseOrder.getExpectedDeliveryDate());
        dto.setRemarks(purchaseOrder.getRemarks());
        dto.setTotalAmount(purchaseOrder.getTotalAmount());

        // Vendor info
        if (purchaseOrder.getVendor() != null) {
            dto.setVendorId(purchaseOrder.getVendor().getId());
            dto.setVendorName(purchaseOrder.getVendor().getVendorName());
        }

        // Indent info
        if (purchaseOrder.getIndent() != null) {
            dto.setIndentId(purchaseOrder.getIndent().getId());
            dto.setIndentNumber(purchaseOrder.getIndent().getIndentNumber());
        }

        // Project info
        if (purchaseOrder.getProject() != null) {
            dto.setProjectId(purchaseOrder.getProject().getId());
            dto.setProjectName(purchaseOrder.getProject().getProjectName());
        }

        // Created by
        if (purchaseOrder.getCreatedBy() != null) {
            dto.setCreatedBy(EmployeeDtoConvertor.convertEmployeeToDto(purchaseOrder.getCreatedBy(), fileStorageService));
        }

        // Items
        if (purchaseOrder.getItems() != null && !purchaseOrder.getItems().isEmpty()) {
            List<PurchaseOrderItemDto> itemDtos = purchaseOrder.getItems().stream()
                    .map(PurchaseOrderDtoConvertor::convertItemToDto)
                    .collect(Collectors.toList());
            dto.setItems(itemDtos);
        }

        return dto;
    }

    public static PurchaseOrderItemDto convertItemToDto(PurchaseOrderItem item) {
        if (item == null) {
            return null;
        }

        PurchaseOrderItemDto dto = new PurchaseOrderItemDto();
        dto.setId(item.getId());
        dto.setOrderedQuantity(item.getOrderedQuantity());
        dto.setReceivedQuantity(item.getReceivedQuantity());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setTotalPrice(item.getTotalPrice());
        dto.setRemarks(item.getRemarks());

        // Material info
        if (item.getMaterial() != null) {
            dto.setMaterialId(item.getMaterial().getId());
            dto.setMaterialName(item.getMaterial().getMaterialName());
        }

        // IndentItem info
        if (item.getIndentItem() != null) {
            dto.setIndentItemId(item.getIndentItem().getId());
        }

        return dto;
    }
}

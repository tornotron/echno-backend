package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;

@Component
public class InventoryTransactionDtoConvertor {

    public static InventoryTransactionDto convertToDto(InventoryTransaction transaction, FileStorageService fileStorageService) {
        if (transaction == null) {
            return null;
        }

        InventoryTransactionDto dto = new InventoryTransactionDto();
        dto.setId(transaction.getId());
        dto.setTransactionDate(transaction.getTransactionDate());
        dto.setOpeningStock(transaction.getOpeningStock());
        dto.setQuantityChanged(transaction.getQuantityChanged());
        dto.setClosingStock(transaction.getClosingStock());
        dto.setTransactionType(transaction.getTransactionType());
        dto.setReferenceNumber(transaction.getReferenceNumber());
        dto.setRemarks(transaction.getRemarks());

        // Material info
        if (transaction.getMaterial() != null) {
            dto.setMaterialId(transaction.getMaterial().getId());
            dto.setMaterialName(transaction.getMaterial().getMaterialName());
        }

        // Project info
        if (transaction.getProject() != null) {
            dto.setProjectId(transaction.getProject().getId());
            dto.setProjectName(transaction.getProject().getProjectName());
        }

        // Created by
        if (transaction.getCreatedBy() != null) {
            dto.setCreatedBy(EmployeeDtoConvertor.convertEmployeeToDto(transaction.getCreatedBy(), fileStorageService));
        }

        return dto;
    }
}

package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SiteTransferDtoConvertor {

    public static SiteTransferDto convertToDto(SiteTransfer transfer, FileStorageService fileStorageService) {
        if (transfer == null) {
            return null;
        }

        SiteTransferDto dto = new SiteTransferDto();
        dto.setId(transfer.getId());
        dto.setTransferNumber(transfer.getTransferNumber());
        dto.setIssueDate(transfer.getIssueDate());
        dto.setStatus(transfer.getStatus());

        // Sending person
        if (transfer.getSendingPerson() != null) {
            dto.setSendingPerson(EmployeeDtoConvertor.convertEmployeeToDto(transfer.getSendingPerson(), fileStorageService));
        }

        // Sending project
        if (transfer.getSendingProject() != null) {
            dto.setSendingProjectId(transfer.getSendingProject().getId());
            dto.setSendingProjectName(transfer.getSendingProject().getProjectName());
        }

        // Sending storage location
        if (transfer.getSendingStorageLocation() != null) {
            dto.setSendingStorageLocationId(transfer.getSendingStorageLocation().getId());
            dto.setSendingStorageLocationName(transfer.getSendingStorageLocation().getLocationName());
        }

        // Receiving project
        if (transfer.getReceivingProject() != null) {
            dto.setReceivingProjectId(transfer.getReceivingProject().getId());
            dto.setReceivingProjectName(transfer.getReceivingProject().getProjectName());
        }

        // Receiving storage location
        if (transfer.getReceivingStorageLocation() != null) {
            dto.setReceivingStorageLocationId(transfer.getReceivingStorageLocation().getId());
            dto.setReceivingStorageLocationName(transfer.getReceivingStorageLocation().getLocationName());
        }

        // Items
        if (transfer.getItems() != null && !transfer.getItems().isEmpty()) {
            List<SiteTransferItemDto> itemDtos = transfer.getItems().stream()
                    .map(SiteTransferDtoConvertor::convertItemToDto)
                    .collect(Collectors.toList());
            dto.setItems(itemDtos);
        }

        return dto;
    }

    public static SiteTransferItemDto convertItemToDto(SiteTransferItem item) {
        if (item == null) {
            return null;
        }

        SiteTransferItemDto dto = new SiteTransferItemDto();
        dto.setId(item.getId());
        dto.setSentQuantity(item.getSentQuantity());
        dto.setRemarks(item.getRemarks());

        // Material info
        if (item.getMaterial() != null) {
            dto.setMaterialId(item.getMaterial().getId());
            dto.setMaterialName(item.getMaterial().getMaterialName());
        }

        return dto;
    }
}

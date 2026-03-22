package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;

import java.util.Collections;
import java.util.stream.Collectors;

@Component
public class IndentDtoConvertor {

    public static IndentDto convertIndentToDto(Indent indent, FileStorageService fileStorageService, InventoryService inventoryService) {
        IndentDto dto = new IndentDto();
        dto.setId(indent.getId());
        dto.setCreatedBy(EmployeeDtoConvertor.convertEmployeeToDto(indent.getCreatedBy(),fileStorageService));
        dto.setIndentNumber(indent.getIndentNumber());
        dto.setCreatedAt(indent.getCreatedAt());
        dto.setStatus(indent.getStatus());
        dto.setExpectedOn(indent.getExpectedOn());
        dto.setRemarks(indent.getRemarks());

        // Project info
        if (indent.getProject() != null) {
            dto.setProjectId(indent.getProject().getId());
            dto.setProjectName(indent.getProject().getProjectName());
        }

        if (indent.getItems() != null) {
            dto.setItems(indent.getItems().stream()
                    .map(indentItem -> IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService,inventoryService))
                    .collect(Collectors.toList()));
        } else {
            dto.setItems(Collections.emptyList());
        }

        return dto;
    }
}

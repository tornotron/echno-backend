package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.intend.Intend;
import org.tornotron.echno_backend.intend.dto.IntendDto;

import java.util.Collections;
import java.util.stream.Collectors;

@Component
public class IntendDtoConvertor {

    public static IntendDto convertIntendToDto(Intend intend, FileStorageService fileStorageService) {
        IntendDto dto = new IntendDto();
        dto.setId(intend.getId());
        dto.setCreatedBy(EmployeeDtoConvertor.convertEmployeeToDto(intend.getCreatedBy(),fileStorageService));
        dto.setIntendNumber(intend.getIntendNumber());
        dto.setCreatedAt(intend.getCreatedAt());
        dto.setStatus(intend.getStatus());
        dto.setExpectedOn(intend.getExpectedOn());
        dto.setRemarks(intend.getRemarks());

        if (intend.getItems() != null) {
            dto.setItems(intend.getItems().stream()
                    .map(indentItem -> IndentItemDtoConvertor.convertIndentItemToDto(indentItem,fileStorageService))
                    .collect(Collectors.toList()));
        } else {
            dto.setItems(Collections.emptyList());
        }

        return dto;
    }
}

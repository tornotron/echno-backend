package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.wbs.WbsElement;
import org.tornotron.echno_backend.wbs.dto.WbsElementDto;
import org.tornotron.echno_backend.wbs.dto.WbsElementFlatDto;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class WbsElementDtoConvertor {

    public static WbsElementDto convertToDto(WbsElement element, FileStorageService fileStorageService) {
        WbsElementDto dto = new WbsElementDto();
        dto.setId(element.getId());
        dto.setWbsCode(element.getWbsCode());
        dto.setTitle(element.getTitle());
        dto.setDescription(element.getDescription());
        dto.setLevel(element.getLevel());
        dto.setSortOrder(element.getSortOrder());
        dto.setStatus(element.getStatus());
        dto.setStartDate(element.getStartDate());
        dto.setEndDate(element.getEndDate());
        dto.setActualStartDate(element.getActualStartDate());
        dto.setActualEndDate(element.getActualEndDate());
        dto.setBudgetedCost(element.getBudgetedCost());
        dto.setActualCost(element.getActualCost());
        dto.setProgress(element.getProgress());
        dto.setWeight(element.getWeight());
        dto.setIsLeaf(element.getIsLeaf());
        dto.setProjectId(element.getProject().getId());
        dto.setProjectName(element.getProject().getProjectName());
        dto.setCreatedAt(element.getCreatedAt());
        dto.setUpdatedAt(element.getUpdatedAt());

        if (element.getParent() != null) {
            dto.setParentId(element.getParent().getId());
            dto.setParentWbsCode(element.getParent().getWbsCode());
        }

        if (element.getCreatedBy() != null) {
            dto.setCreatedBy(EmployeeDtoConvertor.convertEmployeeToDto(element.getCreatedBy(), fileStorageService));
        }

        return dto;
    }

    public static WbsElementDto convertToTreeDto(WbsElement element, FileStorageService fileStorageService) {
        WbsElementDto dto = convertToDto(element, fileStorageService);

        if (element.getChildren() != null && !element.getChildren().isEmpty()) {
            List<WbsElementDto> childDtos = element.getChildren().stream()
                    .map(child -> convertToTreeDto(child, fileStorageService))
                    .collect(Collectors.toList());
            dto.setChildren(childDtos);
        } else {
            dto.setChildren(List.of());
        }

        return dto;
    }

    public static WbsElementFlatDto convertToFlatDto(WbsElement element) {
        WbsElementFlatDto dto = new WbsElementFlatDto();
        dto.setId(element.getId());
        dto.setWbsCode(element.getWbsCode());
        dto.setTitle(element.getTitle());
        dto.setLevel(element.getLevel());
        dto.setSortOrder(element.getSortOrder());
        dto.setStatus(element.getStatus());
        dto.setBudgetedCost(element.getBudgetedCost());
        dto.setActualCost(element.getActualCost());
        dto.setProgress(element.getProgress());
        dto.setIsLeaf(element.getIsLeaf());

        if (element.getParent() != null) {
            dto.setParentId(element.getParent().getId());
        }

        return dto;
    }
}

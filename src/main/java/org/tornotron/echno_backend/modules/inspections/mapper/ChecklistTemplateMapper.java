package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.util.List;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplate;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplateItem;
import org.tornotron.echno_backend.modules.inspections.domain.StarterChecklistTemplate;
import org.tornotron.echno_backend.modules.inspections.domain.StarterChecklistTemplateItem;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateItemDto;
import org.tornotron.echno_backend.modules.inspections.dtos.StarterChecklistTemplateDto;

@Mapper(componentModel = "spring")
public interface ChecklistTemplateMapper {

    @Mapping(target = "trade", source = "tradeRef.code")
    @Mapping(target = "tradeId", source = "tradeRef.id")
    @Mapping(target = "tradeName", source = "tradeRef.name")
    @Mapping(target = "tradeGroup", source = "tradeRef.groupCode")
    ChecklistTemplateDto toDto(ChecklistTemplate template);

    ChecklistTemplateItemDto toItemDto(ChecklistTemplateItem item);

    default List<ProjectType> projectTypes(List<String> names) {
        return names == null ? null : names.stream().map(ProjectType::valueOf).toList();
    }

    @Mapping(target = "trade", source = "tradeCode")
    StarterChecklistTemplateDto toStarterDto(StarterChecklistTemplate template);

    ChecklistTemplateItemDto toStarterItemDto(StarterChecklistTemplateItem item);
}

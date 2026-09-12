package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplate;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplateItem;
import org.tornotron.echno_backend.modules.inspections.domain.StarterChecklistTemplate;
import org.tornotron.echno_backend.modules.inspections.domain.StarterChecklistTemplateItem;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateItemDto;
import org.tornotron.echno_backend.modules.inspections.dtos.StarterChecklistTemplateDto;

@Mapper(componentModel = "spring")
public interface ChecklistTemplateMapper {

    ChecklistTemplateDto toDto(ChecklistTemplate template);

    ChecklistTemplateItemDto toItemDto(ChecklistTemplateItem item);

    StarterChecklistTemplateDto toStarterDto(StarterChecklistTemplate template);

    ChecklistTemplateItemDto toStarterItemDto(StarterChecklistTemplateItem item);
}

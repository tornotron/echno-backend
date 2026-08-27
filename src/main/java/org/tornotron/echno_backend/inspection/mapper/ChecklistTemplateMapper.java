package org.tornotron.echno_backend.inspection.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.inspection.domain.ChecklistTemplate;
import org.tornotron.echno_backend.inspection.domain.ChecklistTemplateItem;
import org.tornotron.echno_backend.inspection.domain.StarterChecklistTemplate;
import org.tornotron.echno_backend.inspection.domain.StarterChecklistTemplateItem;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateDto;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateItemDto;
import org.tornotron.echno_backend.inspection.dtos.StarterChecklistTemplateDto;

@Mapper(componentModel = "spring")
public interface ChecklistTemplateMapper {

    ChecklistTemplateDto toDto(ChecklistTemplate template);

    ChecklistTemplateItemDto toItemDto(ChecklistTemplateItem item);

    StarterChecklistTemplateDto toStarterDto(StarterChecklistTemplate template);

    ChecklistTemplateItemDto toStarterItemDto(StarterChecklistTemplateItem item);
}

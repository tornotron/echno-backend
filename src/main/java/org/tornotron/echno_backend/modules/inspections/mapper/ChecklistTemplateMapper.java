package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplate;
import org.tornotron.echno_backend.modules.inspections.domain.ChecklistTemplateItem;
import org.tornotron.echno_backend.modules.inspections.domain.StarterChecklistTemplate;
import org.tornotron.echno_backend.modules.inspections.domain.StarterChecklistTemplateItem;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateItemDto;
import org.tornotron.echno_backend.modules.inspections.dtos.StarterChecklistTemplateDto;

@Mapper(componentModel = "spring")
public interface ChecklistTemplateMapper {

    @Mapping(target = "trade", expression = "java(tradeCode(template.getTradeRef(), template.getTrade()))")
    @Mapping(target = "tradeId", source = "tradeRef.id")
    @Mapping(target = "tradeName", source = "tradeRef.name")
    @Mapping(target = "tradeGroup", source = "tradeRef.groupCode")
    ChecklistTemplateDto toDto(ChecklistTemplate template);

    ChecklistTemplateItemDto toItemDto(ChecklistTemplateItem item);

    @Mapping(target = "trade", source = "tradeCode")
    StarterChecklistTemplateDto toStarterDto(StarterChecklistTemplate template);

    /** The slug on the wire: the org row's code, or the enum's value while a row is missing. */
    @SuppressWarnings("deprecation")
    default String tradeCode(OrgTrade ref, org.tornotron.echno_backend.modules.inspections.InspectionTrade legacy) {
        if (ref != null) {
            return ref.getCode();
        }
        return legacy == null ? null : legacy.getValue();
    }

    ChecklistTemplateItemDto toStarterItemDto(StarterChecklistTemplateItem item);
}

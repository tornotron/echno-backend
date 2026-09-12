package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionCheckItem;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;

@Mapper(componentModel = "spring")
public interface InspectionMapper {

    @Mapping(target = "trade", expression = "java(tradeCode(inspection.getTradeRef(), inspection.getTrade()))")
    @Mapping(target = "tradeId", source = "tradeRef.id")
    @Mapping(target = "tradeName", source = "tradeRef.name")
    @Mapping(target = "tradeGroup", source = "tradeRef.groupCode")
    InspectionDto toDto(Inspection inspection);

    /** The slug on the wire: the org row's code, or the enum's value while a row is missing. */
    @SuppressWarnings("deprecation")
    default String tradeCode(OrgTrade ref, org.tornotron.echno_backend.modules.inspections.InspectionTrade legacy) {
        if (ref != null) {
            return ref.getCode();
        }
        return legacy == null ? null : legacy.getValue();
    }

    InspectionCheckItemDto toCheckItemDto(InspectionCheckItem item);

    InspectionDefectDto toDefectDto(InspectionDefect defect);
}

package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionCheckItem;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;

@Mapper(componentModel = "spring")
public interface InspectionMapper {

    InspectionDto toDto(Inspection inspection);

    InspectionCheckItemDto toCheckItemDto(InspectionCheckItem item);

    InspectionDefectDto toDefectDto(InspectionDefect defect);
}

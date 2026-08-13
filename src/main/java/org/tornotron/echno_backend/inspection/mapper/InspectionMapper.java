package org.tornotron.echno_backend.inspection.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.domain.InspectionCheckItem;
import org.tornotron.echno_backend.inspection.domain.InspectionDefect;
import org.tornotron.echno_backend.inspection.dtos.InspectionCheckItemDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionDefectDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;

@Mapper(componentModel = "spring")
public interface InspectionMapper {

    InspectionDto toDto(Inspection inspection);

    InspectionCheckItemDto toCheckItemDto(InspectionCheckItem item);

    InspectionDefectDto toDefectDto(InspectionDefect defect);
}

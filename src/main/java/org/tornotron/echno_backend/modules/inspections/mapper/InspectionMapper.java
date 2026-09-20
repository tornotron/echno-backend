package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionCheckItem;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionCheckItemDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDefectDto;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionDto;

@Mapper(componentModel = "spring")
public interface InspectionMapper {

    @Mapping(target = "trade", source = "tradeRef.code")
    @Mapping(target = "tradeId", source = "tradeRef.id")
    @Mapping(target = "tradeName", source = "tradeRef.name")
    @Mapping(target = "tradeGroup", source = "tradeRef.groupCode")
    @Mapping(target = "spatialPath", expression = "java(java.util.List.of())")
    InspectionDto toDto(Inspection inspection);

    @Mapping(target = "spatialPath", expression = "java(java.util.List.of())")
    InspectionCheckItemDto toCheckItemDto(InspectionCheckItem item);

    @Mapping(target = "spatialPath", expression = "java(java.util.List.of())")
    @Mapping(target = "inspectionId", source = "inspection.id")
    @Mapping(target = "inspectionNumber", source = "inspection.inspectionNumber")
    @Mapping(target = "inspectionTitle", source = "inspection.title")
    @Mapping(target = "projectId", source = "inspection.projectId")
    @Mapping(target = "projectName", ignore = true) // filled by InspectionService from one name read
    InspectionDefectDto toDefectDto(InspectionDefect defect);
}

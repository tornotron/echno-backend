package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.inspections.domain.Reinspection;
import org.tornotron.echno_backend.modules.inspections.dtos.ReinspectionDto;

@Mapper(componentModel = "spring")
public interface ReinspectionMapper {
    ReinspectionDto toDto(Reinspection reinspection);
}

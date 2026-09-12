package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.dtos.ObservationDto;

@Mapper(componentModel = "spring")
public interface ObservationMapper {

    @Mapping(target = "spatialPath", expression = "java(java.util.List.of())")
    ObservationDto toDto(Observation observation);
}

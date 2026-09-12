package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.inspections.domain.DefectPhotoAnnotation;
import org.tornotron.echno_backend.modules.inspections.dtos.DefectPhotoAnnotationDto;

@Mapper(componentModel = "spring")
public interface DefectPhotoAnnotationMapper {

    DefectPhotoAnnotationDto toDto(DefectPhotoAnnotation annotation);
}

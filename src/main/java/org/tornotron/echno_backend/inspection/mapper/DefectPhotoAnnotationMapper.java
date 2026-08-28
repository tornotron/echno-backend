package org.tornotron.echno_backend.inspection.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.inspection.domain.DefectPhotoAnnotation;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationDto;

@Mapper(componentModel = "spring")
public interface DefectPhotoAnnotationMapper {

    DefectPhotoAnnotationDto toDto(DefectPhotoAnnotation annotation);
}

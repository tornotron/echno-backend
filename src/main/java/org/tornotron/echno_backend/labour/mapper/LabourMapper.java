package org.tornotron.echno_backend.labour.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.labour.Labour;
import org.tornotron.echno_backend.labour.dto.LabourDto;
import org.tornotron.echno_backend.labour.dto.LabourSimpleDto;

/**
 * Maps {@link Labour} to its DTOs. The full DTO keeps the enum-typed fields; the
 * simple DTO flattens the organization and current-project associations and renders
 * the enums and the project id as strings, matching the previous converter (the enums
 * do not override toString, so MapStruct's name() output is identical).
 */
@Mapper(componentModel = "spring")
public interface LabourMapper {

    LabourDto toDto(Labour labour);

    @Mapping(source = "labourID", target = "labourId")
    @Mapping(source = "organization.id", target = "organizationId")
    @Mapping(source = "organization.organizationName", target = "organizationName")
    @Mapping(source = "currentProject.id", target = "currentProjectId")
    @Mapping(source = "currentProject.projectName", target = "currentProjectName")
    LabourSimpleDto toSimpleDto(Labour labour);
}

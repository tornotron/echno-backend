package org.tornotron.echno_backend.projectInviteCode.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.projectInviteCode.ProjectInviteCode;
import org.tornotron.echno_backend.projectInviteCode.dto.ProjectInviteCodeDto;

/** Maps {@link ProjectInviteCode} to its DTO. All fields map by name. */
@Mapper(componentModel = "spring")
public interface ProjectInviteCodeMapper {

    ProjectInviteCodeDto toDto(ProjectInviteCode projectInviteCode);
}

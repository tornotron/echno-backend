package org.tornotron.echno_backend.organization.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;

/**
 * Maps {@link Organization} to its DTOs. employees via {@link EmployeeMapper}, projects via
 * {@link ProjectMapper} (the full nested tree), attachments via {@link AttachmentMapper};
 * scalar fields by name. The simple DTO omits the associations.
 */
@Mapper(componentModel = "spring",
        uses = {EmployeeMapper.class, ProjectMapper.class, AttachmentMapper.class})
public interface OrganizationMapper {

    OrganizationDto toDto(Organization organization);

    OrganizationSimpleDto toSimpleDto(Organization organization);
}

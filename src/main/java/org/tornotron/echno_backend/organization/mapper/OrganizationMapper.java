package org.tornotron.echno_backend.organization.mapper;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationSummaryLookup;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;

/**
 * Maps {@link Organization} to its DTOs. employees via {@link EmployeeMapper}, projects via
 * {@link ProjectMapper} (the full nested tree), attachments via {@link AttachmentMapper};
 * scalar fields by name. The simple DTO omits the associations.
 *
 * <p>{@link #toSummaryDto} is the list projection: the simple DTO with the counts and the logo
 * URL the organization cards render, read for the whole list in one query and handed in as a
 * {@link OrganizationSummaryLookup}. {@link #toSimpleDto} is the shape a create or an update
 * replies with; it leaves the counts null and the logo unresolved, because those replies do not
 * read them.
 */
@Mapper(componentModel = "spring",
        uses = {EmployeeMapper.class, ProjectMapper.class, AttachmentMapper.class})
public interface OrganizationMapper {

    OrganizationDto toDto(Organization organization);

    @Mapping(target = "employeeCount", ignore = true) // filled only by toSummaryDto
    @Mapping(target = "projectCount", ignore = true)
    @Mapping(target = "logoUrl", ignore = true)
    OrganizationSimpleDto toSimpleDto(Organization organization);

    /**
     * Converts an organization for a list, taking its counts and its logo from the supplied lookup.
     *
     * @param organization The organization to convert.
     * @param totals The employee count, project count and logo URL read for the whole list of
     *               organizations being mapped. An organization absent from it reads as zero of
     *               each count and no logo.
     * @return The organization summary.
     */
    @Mapping(target = "employeeCount",
            expression = "java(totals.employeeCountOf(organization.getId()))")
    @Mapping(target = "projectCount",
            expression = "java(totals.projectCountOf(organization.getId()))")
    @Mapping(target = "logoUrl", expression = "java(totals.logoUrlOf(organization.getId()))")
    OrganizationSimpleDto toSummaryDto(Organization organization,
                                       @Context OrganizationSummaryLookup totals);
}

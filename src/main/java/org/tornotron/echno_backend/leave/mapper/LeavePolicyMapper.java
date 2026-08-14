package org.tornotron.echno_backend.leave.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.leave.LeavePolicy;
import org.tornotron.echno_backend.leave.dto.LeavePolicyDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicySimpleDto;

/** Maps {@link LeavePolicy} to its full and simple DTOs. The organization flattens to id + name; the rest by name. */
@Mapper(componentModel = "spring")
public interface LeavePolicyMapper {

    @Mapping(source = "organization.id", target = "organizationId")
    @Mapping(source = "organization.organizationName", target = "organizationName")
    LeavePolicyDto toDto(LeavePolicy policy);

    LeavePolicySimpleDto toSimpleDto(LeavePolicy policy);
}

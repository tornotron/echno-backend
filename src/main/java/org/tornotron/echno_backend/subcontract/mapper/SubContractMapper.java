package org.tornotron.echno_backend.subcontract.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.subcontract.ContractMilestone;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.subcontract.dto.ContractMilestoneDto;
import org.tornotron.echno_backend.subcontract.dto.SubContractDto;

/**
 * Maps {@link SubContract} and its milestones to their DTOs. Organization flattens
 * to id; the milestone collection maps element-wise via {@link #toMilestoneDto}.
 */
@Mapper(componentModel = "spring")
public interface SubContractMapper {

    @Mapping(source = "organization.id", target = "organizationId")
    SubContractDto toDto(SubContract subContract);

    ContractMilestoneDto toMilestoneDto(ContractMilestone milestone);
}

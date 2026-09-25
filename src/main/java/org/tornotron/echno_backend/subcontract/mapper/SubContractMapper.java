package org.tornotron.echno_backend.subcontract.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.subcontract.ContractMilestone;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.subcontract.dto.ContractMilestoneDto;
import org.tornotron.echno_backend.subcontract.dto.SubContractDto;
import org.tornotron.echno_backend.subcontract.enums.SubContractPaymentStatus;

import java.time.LocalDate;

/**
 * Maps {@link SubContract} and its milestones to their DTOs. Organization flattens
 * to id; the milestone collection maps element-wise via {@link #toMilestoneDto}.
 */
@Mapper(componentModel = "spring")
public interface SubContractMapper {

    @Mapping(source = "organization.id", target = "organizationId")
    @Mapping(target = "paymentStatus", expression = "java(paymentStatusOf(subContract))")
    SubContractDto toDto(SubContract subContract);

    /** The derived payment status the list badge shows (#863). */
    default String paymentStatusOf(SubContract subContract) {
        return SubContractPaymentStatus.derive(
                subContract.getContractValue(),
                subContract.getTotalPaid(),
                subContract.getEndDate(),
                LocalDate.now()).value();
    }

    ContractMilestoneDto toMilestoneDto(ContractMilestone milestone);
}

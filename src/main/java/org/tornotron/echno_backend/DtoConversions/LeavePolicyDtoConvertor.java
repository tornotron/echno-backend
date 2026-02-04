package org.tornotron.echno_backend.DtoConversions;

import org.tornotron.echno_backend.leave.LeavePolicy;
import org.tornotron.echno_backend.leave.dto.LeavePolicyDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicySimpleDto;

public class LeavePolicyDtoConvertor {

    public static LeavePolicyDto convertToDto(LeavePolicy policy) {
        if (policy == null) return null;

        LeavePolicyDto dto = new LeavePolicyDto();
        dto.setId(policy.getId());
        dto.setOrganizationId(policy.getOrganization().getId());
        dto.setOrganizationName(policy.getOrganization().getOrganizationName());
        dto.setLeaveTypeCode(policy.getLeaveTypeCode());
        dto.setLeaveTypeName(policy.getLeaveTypeName());
        dto.setDescription(policy.getDescription());
        dto.setAnnualQuota(policy.getAnnualQuota());
        dto.setAccrualRatePerMonth(policy.getAccrualRatePerMonth());
        dto.setCarryForwardLimit(policy.getCarryForwardLimit());
        dto.setCarryForwardExpiryMonths(policy.getCarryForwardExpiryMonths());
        dto.setMinDaysPerRequest(policy.getMinDaysPerRequest());
        dto.setMaxDaysPerRequest(policy.getMaxDaysPerRequest());
        dto.setAdvanceNoticeDays(policy.getAdvanceNoticeDays());
        dto.setRequiresAttachment(policy.getRequiresAttachment());
        dto.setAttachmentRequiredAfterDays(policy.getAttachmentRequiredAfterDays());
        dto.setApplicableGenders(policy.getApplicableGenders());
        dto.setMinServiceMonths(policy.getMinServiceMonths());
        dto.setAllowHalfDay(policy.getAllowHalfDay());
        dto.setIsPaid(policy.getIsPaid());
        dto.setIsActive(policy.getIsActive());
        dto.setDisplayOrder(policy.getDisplayOrder());
        dto.setCreatedAt(policy.getCreatedAt());
        dto.setUpdatedAt(policy.getUpdatedAt());
        return dto;
    }

    public static LeavePolicySimpleDto convertToSimpleDto(LeavePolicy policy) {
        if (policy == null) return null;

        LeavePolicySimpleDto dto = new LeavePolicySimpleDto();
        dto.setId(policy.getId());
        dto.setLeaveTypeCode(policy.getLeaveTypeCode());
        dto.setLeaveTypeName(policy.getLeaveTypeName());
        dto.setAnnualQuota(policy.getAnnualQuota());
        dto.setAllowHalfDay(policy.getAllowHalfDay());
        dto.setIsPaid(policy.getIsPaid());
        return dto;
    }
}

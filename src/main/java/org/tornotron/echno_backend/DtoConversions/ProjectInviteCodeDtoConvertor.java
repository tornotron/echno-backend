package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.projectInviteCode.ProjectInviteCode;
import org.tornotron.echno_backend.projectInviteCode.dto.ProjectInviteCodeDto;

@Component
public class ProjectInviteCodeDtoConvertor {

    public static ProjectInviteCodeDto convertToDto(ProjectInviteCode projectInviteCode) {
        ProjectInviteCodeDto dto = new ProjectInviteCodeDto();
        dto.setId(projectInviteCode.getId());
        dto.setCode(projectInviteCode.getCode());
        dto.setActive(projectInviteCode.isActive());
        dto.setMaxUses(projectInviteCode.getMaxUses());
        dto.setCurrentUses(projectInviteCode.getCurrentUses());
        dto.setExpiryDate(projectInviteCode.getExpiryDate());
        dto.setEmployeeDetails(projectInviteCode.getEmployeeDetails());
        return dto;
    }
}

package org.tornotron.echno_backend.employee.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.tornotron.echno_backend.common.enums.OrgRole;

@Data
public class OrgRoleAssignmentDto {
    @NotNull(message = "role is required")
    private OrgRole role;
}

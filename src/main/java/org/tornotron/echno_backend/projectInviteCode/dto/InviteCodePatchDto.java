package org.tornotron.echno_backend.projectInviteCode.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class InviteCodePatchDto {

    @Min(value = 0, message = "maxUses must be at least 0")
    private Integer maxUses;

    @Min(value = 0, message = "currentUses must be at least 0")
    private Integer currentUses;

    private Boolean isActive;
}

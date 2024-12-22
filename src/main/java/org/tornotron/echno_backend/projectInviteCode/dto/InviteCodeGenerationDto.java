package org.tornotron.echno_backend.projectInviteCode.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InviteCodeGenerationDto {

    @NotNull(message = "projectName is a required request parameter")
    String projectName;

    int maxUses = 1;

    int validityDays = 5;
}

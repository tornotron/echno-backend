package org.tornotron.echno_backend.projectInviteCode.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InviteCodeGenerationDto {

    @NotNull(message = "projectName is a required request parameter")
    @Size(min = 3,max = 50,message = "projectName must be between 3 and 50 characters")
    String projectName;

    int maxUses = 1;

    int validityDays = 5;
}

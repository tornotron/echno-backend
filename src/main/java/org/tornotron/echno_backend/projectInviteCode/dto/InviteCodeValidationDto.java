package org.tornotron.echno_backend.projectInviteCode.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InviteCodeValidationDto {
    @NotNull(message = "code is a required request parameter")
    int code;
}

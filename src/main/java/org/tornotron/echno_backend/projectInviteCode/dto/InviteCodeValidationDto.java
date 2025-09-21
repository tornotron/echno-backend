package org.tornotron.echno_backend.projectInviteCode.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InviteCodeValidationDto {

    @NotBlank(message = "code is a required request parameter")
    @Size(min = 5,max = 5,message = "code must be exactly 5 characters")
    String code;

    @NotNull(message = "userId is a required request parameter")
    Long userId;
}

package org.tornotron.echno_backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserRegistrationDto {

    @NotBlank(message = "name is required")
    @Size(min = 3, max = 50, message = "name must be between 3 and 50 characters")
    private String name;

    @NotBlank(message = "email is required")
    @Email
    @Size(max = 100, message = "email must be at most 100 characters")
    private String email;

    @NotBlank(message = "password is required")
    private String password;
}

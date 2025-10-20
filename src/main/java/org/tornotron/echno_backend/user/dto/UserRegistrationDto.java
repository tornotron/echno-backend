package org.tornotron.echno_backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserRegistrationDto {

    @NotBlank(message = "userName is required")
    @Size(min = 3, max = 50, message = "userName must be between 3 and 50 characters")
    @Pattern(regexp = "\\S+",message = "userName cannot contain any space")
    private String userName;

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "phone is required")
    private String phone;

    @NotBlank(message = "gender is required")
    private String gender;

    @NotBlank(message = "dateOfBirth is required")
    private LocalDateTime dateOfBirth;

    private String role;

    private Boolean acceptTerms;

    @NotBlank(message = "email is required")
    @Email
    @Size(max = 100, message = "email must be at most 100 characters")
    private String email;

    @NotBlank(message = "password is required")
    private String password;
}

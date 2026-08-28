package org.tornotron.echno_backend.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import jakarta.validation.constraints.*;
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

    @NotNull(message = "dateOfBirth is required")
    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    private LocalDateTime dateOfBirth;

    private String role;

    private Boolean acceptTerms;

    @NotBlank(message = "email is required")
    @Email
    @Size(max = 100, message = "email must be at most 100 characters")
    private String email;

    @NotBlank(message = "password is required")
    private String password;

    private Long defaultOrganizationId;
}

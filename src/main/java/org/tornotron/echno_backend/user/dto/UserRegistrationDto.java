package org.tornotron.echno_backend.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * The profile a signed-in account fills in to become a user of the application.
 *
 * <p>There is no username, email or password here. The account already exists at the auth
 * provider, which is where the person signed up and where their credentials live; registration
 * binds that account, by the subject on the caller's token, to a user row holding this profile.
 * The email is taken from the token too, because an address the provider has verified is the only
 * one worth keying the organization lookups on.
 */
@Data
public class UserRegistrationDto {

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

    private Long defaultOrganizationId;
}

package org.tornotron.echno_backend.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserCreationDto {

    @NotBlank(message = "userName is required")
    @Size(min = 3,max = 50, message = "userName must be between 3 and 50 characters")
    private String userName;

    @NotBlank(message = "password is required")
    private String password;

    @NotBlank(message = "name is required")
    @Size(min = 3, max = 50, message = "name must be between 3 and 50 characters")
    private String name;

    @Size(max = 10, message = "bloodGroup must be at most 10 characters")
    private String bloodGroup;

    @NotBlank(message = "gender is required")
    private String gender;

    private String address;

    @NotBlank(message = "email is required")
    @Email
    @Size(max = 100, message = "email must be at most 100 characters")
    private String email;

    @NotBlank(message = "phone is required")
    @Size(min = 10, max = 15, message = "phone must be between 10 and 15 characters")
    private String phone;

    @Past(message = "dateOfBirth must be a past date")
    @NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    private LocalDateTime dateOfBirth;

    @NotBlank(message = "qualification is required")
    private String qualification;

    private List<String> skills;

    private List<String> certifications;

    private Integer experience;

    private String cvUrl;

    private String emergencyContact;

    @Enumerated(EnumType.STRING)
    @NotBlank(message = "role is required")
    private String role;

    private String profilePictureUrl;

    private Long defaultOrganizationId;
}

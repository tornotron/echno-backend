package org.tornotron.echno_backend.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrganizationCreationDto {

    @NotBlank(message = "organization is required")
    @Size(min = 3, max = 50, message = "organization name must be between 3 and 50 characters")
    private String organizationName;

    @NotBlank(message = "organizationAddress is required")
    @Size(min = 3, max = 50, message = "organizationAddress must be between 3 and 50 characters")
    private String organizationAddress;

    @NotBlank(message = "organizationEmail is required")
    @Pattern(
            regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$",
            message = "Invalid email address format"
    )
    @Size(max = 255, message = "organizationEmail must be at most 255 characters")
    private String organizationEmail;

    @NotBlank(message = "organizationPhone is required")
    @Size(min = 10,max = 15,message = "memberPhone must be between 10 and 15 characters")
    private String organizationPhone;

    @Size(max = 255, message = "organizationWebsite must be at most 255 characters")
    private String organizationWebsite;

    private String organizationLogo;

    private LocalDateTime createdAt;

}

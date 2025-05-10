package org.tornotron.echno_backend.organization.dto;

import jakarta.validation.constraints.NotBlank;
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

    private LocalDateTime createdAt;

}

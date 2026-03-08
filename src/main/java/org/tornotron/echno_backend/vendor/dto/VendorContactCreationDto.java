package org.tornotron.echno_backend.vendor.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VendorContactCreationDto {

    @NotBlank(message = "contact person name is required")
    private String contactPerson;

    @NotBlank(message = "email is required")
    @Email(message = "email must be valid")
    private String email;

    @NotBlank(message = "phone is required")
    private String phone;

    private String alternatePhone;

    private boolean isPrimary = false;
}

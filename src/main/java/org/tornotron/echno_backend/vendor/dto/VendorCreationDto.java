package org.tornotron.echno_backend.vendor.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VendorCreationDto {

    @NotBlank(message = "vendor name is required")
    @Size(min = 1, max = 100, message = "vendor name must be between 1 and 100 characters")
    private String vendorName;

    @Size(max = 200, message = "vendor address must not exceed 200 characters")
    private String vendorAddress;

    @NotBlank(message = "vendor email is required")
    @Email(message = "vendor email must be valid")
    private String vendorEmail;

    private String city;

    private String state;

    private String pinCode;

    private String country;

    private String website;

    @NotBlank(message = "type is required")
    private String type;

    @NotBlank(message = "status is required")
    private String status;

    private String notes;

    private Long organizationId;
}

package org.tornotron.echno_backend.vendor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VendorTaxIdentifierCreationDto {

    @NotNull(message = "tax identifier type is required")
    private String type;

    @NotBlank(message = "tax identifier value is required")
    private String value;
}

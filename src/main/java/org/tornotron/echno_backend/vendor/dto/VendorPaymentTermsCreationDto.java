package org.tornotron.echno_backend.vendor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class VendorPaymentTermsCreationDto {

    @NotNull(message = "payment terms type is required")
    @Schema(description = "Credit period agreed with the vendor.", example = "NET30",
            allowableValues = {"IMMEDIATE", "NET15", "NET20", "NET30", "NET60", "NET90"})
    private String paymentTerms;

    private BigDecimal creditLimit;
    private Integer creditDays;
}

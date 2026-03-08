package org.tornotron.echno_backend.vendor.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class VendorPaymentTermsCreationDto {

    @NotNull(message = "payment terms type is required")
    private String paymentTerms;

    private BigDecimal creditLimit;
    private Integer creditDays;
}

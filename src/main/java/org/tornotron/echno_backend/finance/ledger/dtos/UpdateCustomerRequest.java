package org.tornotron.echno_backend.finance.ledger.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record UpdateCustomerRequest(
        @NotBlank
        @Size(max = 200)
        String name,

        @Size(max = 15)
        String gstin,

        @Size(max = 10)
        String pan,

        @Email
        @Size(max = 200)
        String email,

        @Size(max = 20)
        String phone,

        @Valid
        AddressDto billingAddress,

        @DecimalMin("0.0")
        BigDecimal creditLimit,

        @Min(0)
        @Max(365)
        Integer paymentTermsDays

) {
}

package org.tornotron.echno_backend.finance.ledger.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateCustomerRequest {

        @NotBlank @Size(max = 30)
        private String code;

        @NotBlank @Size(max = 200)
        private String name;

        @Size(max = 15)
        private String gstin;

        @Size(max = 10)
        private String pan;

        @Email @Size(max = 200)
        private String email;

        @Size(max = 20)
        private String phone;

        @Valid
        private AddressDto billingAddress;

        @DecimalMin("0.0")
        private BigDecimal creditLimit;

        @Min(0)
        @Max(365)
        private Integer paymentTermsDays;

}
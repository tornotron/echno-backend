package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Schema(description = "Payload to create a customer. The customer code must be unique within the tenant.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateCustomerRequest {

        @Schema(description = "Customer code, unique within the tenant.", example = "CUST-0042")
        @NotBlank @Size(max = 30)
        private String code;

        @Schema(description = "Customer name.", example = "Asset Homes Pvt Ltd")
        @NotBlank @Size(max = 200)
        private String name;

        @Schema(description = "GST identification number.", example = "29ABCDE1234F1Z5")
        @Size(max = 15)
        private String gstin;

        @Schema(description = "Permanent account number.", example = "ABCDE1234F")
        @Size(max = 10)
        private String pan;

        @Schema(description = "Contact email address.", example = "accounts@assethomes.example")
        @Email @Size(max = 200)
        private String email;

        @Schema(description = "Contact phone number.", example = "+91 98765 43210")
        @Size(max = 20)
        private String phone;

        @Schema(description = "Billing address.")
        @Valid
        private AddressDto billingAddress;

        @Schema(description = "Credit limit extended to the customer.", example = "500000.00")
        @DecimalMin("0.0")
        private BigDecimal creditLimit;

        @Schema(description = "Default payment terms in days, between 0 and 365.", example = "30")
        @Min(0)
        @Max(365)
        private Integer paymentTermsDays;

}
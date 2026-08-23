package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A customer with tax registration, contact and billing details, credit limit and "
        + "payment terms.")
public record CustomerDto(
        @Schema(description = "Unique customer id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID id,

        @Schema(description = "Customer code, unique within the tenant.", example = "CUST-0042")
        String code,

        @Schema(description = "Customer name.", example = "Asset Homes Pvt Ltd")
        String name,

        @Schema(description = "GST identification number.", example = "29ABCDE1234F1Z5")
        String gstin,

        @Schema(description = "Permanent account number.", example = "ABCDE1234F")
        String pan,

        @Schema(description = "Contact email address.", example = "accounts@assethomes.example")
        String email,

        @Schema(description = "Contact phone number.", example = "+91 98765 43210")
        String phone,

        @Schema(description = "Billing address.")
        AddressDto billingAddress,

        @Schema(description = "Credit limit extended to the customer.", example = "500000.00")
        BigDecimal creditLimit,

        @Schema(description = "Default payment terms in days.", example = "30")
        Integer paymentTermsDays,

        @Schema(description = "Whether the customer is active.", example = "true")
        boolean active
) {
}

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

        @Schema(description = "GST identification number. Null where the customer is not registered, or the "
                + "number was not recorded.", example = "29ABCDE1234F1Z5", nullable = true)
        String gstin,

        @Schema(description = "Permanent account number. Null where none was recorded.",
                example = "ABCDE1234F", nullable = true)
        String pan,

        @Schema(description = "Contact email address. Null where none was recorded.",
                example = "accounts@assethomes.example", nullable = true)
        String email,

        @Schema(description = "Contact phone number. Null where none was recorded.",
                example = "+91 98765 43210", nullable = true)
        String phone,

        @Schema(description = "Billing address. Null where no part of the address is set: every address column "
                + "on the customer is nullable, and Hibernate leaves an embedded value null when all of its "
                + "columns are.", nullable = true)
        AddressDto billingAddress,

        @Schema(description = "Credit limit extended to the customer. Null where no credit limit was agreed; a "
                + "zero there would mean a limit of zero.", example = "500000.00", nullable = true)
        BigDecimal creditLimit,

        @Schema(description = "Default payment terms in days. Null where no default terms were agreed.",
                example = "30", nullable = true)
        Integer paymentTermsDays,

        @Schema(description = "Whether the customer is active.", example = "true")
        boolean active
) {
}

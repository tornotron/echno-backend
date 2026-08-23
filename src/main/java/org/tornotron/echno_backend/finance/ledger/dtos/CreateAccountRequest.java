package org.tornotron.echno_backend.finance.ledger.dtos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Payload to create an account in the chart of accounts.")
public record CreateAccountRequest(

        // Optional: when blank the code is auto-generated from the parent and
        // existing siblings. Supply a value only to override the generated code.
        @Schema(description = "Account code. When left blank it is generated from the parent and existing "
                + "sibling accounts. Supply a value only to override the generated code.", example = "1100")
        @Size(max = 20) String code,

        @Schema(description = "Account name.", example = "Cash and Cash Equivalents")
        @NotBlank @Size(max = 200) String name,

        // Optional for children (type is inherited from the parent); required for
        // roots, which declare their own type. See typeProvidedForRoot().
        @Schema(description = "Root account type. Optional for a child account, where the type is inherited "
                + "from the parent, and required for a root account, which declares its own type.",
                example = "ASSET")
        @Size(max = 20) String type,

        @Schema(description = "Parent account id. Omit to create a root account.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID parentId,

        @Schema(description = "Optional description of the account.", example = "Petty cash and bank balances")
        @Size(max = 500) String description
) {

    @JsonIgnore
    @AssertTrue(message = "type is required for a root account (when parentId is absent)")
    public boolean isTypeProvidedForRoot() {
        return parentId != null || (type != null && !type.isBlank());
    }
}

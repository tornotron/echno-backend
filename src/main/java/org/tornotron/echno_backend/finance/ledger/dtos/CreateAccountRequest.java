package org.tornotron.echno_backend.finance.ledger.dtos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateAccountRequest(

        // Optional: when blank the code is auto-generated from the parent and
        // existing siblings. Supply a value only to override the generated code.
        @Size(max = 20) String code,

        @NotBlank @Size(max = 200) String name,

        // Optional for children (type is inherited from the parent); required for
        // roots, which declare their own type. See typeProvidedForRoot().
        @Size(max = 20) String type,

        UUID parentId,

        @Size(max = 500) String description
) {

    @JsonIgnore
    @AssertTrue(message = "type is required for a root account (when parentId is absent)")
    public boolean isTypeProvidedForRoot() {
        return parentId != null || (type != null && !type.isBlank());
    }
}

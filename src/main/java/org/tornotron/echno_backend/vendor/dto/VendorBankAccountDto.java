package org.tornotron.echno_backend.vendor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * A vendor bank account, as it is served. A field without {@code nullable = true} is one the
 * schema behind it makes {@code NOT NULL}; see {@code ReviewedResponseSchemas}.
 */
@Data
public class VendorBankAccountDto {

    private Long id;

    private String bankName;

    private String accountNumber;

    @Schema(description = "IFSC code, which only an Indian domestic account has. Null on a "
            + "foreign account, which carries a SWIFT code instead.", nullable = true)
    private String ifscCode;

    @Schema(description = "Name the account is held in, where it differs from the vendor's own "
            + "name. Null where it was not recorded.", nullable = true)
    private String accountHolderName;

    @Schema(description = "SWIFT code, which only an account reachable internationally has. Null "
            + "on a domestic account.", nullable = true)
    private String swift;

    private boolean isDefault;
}

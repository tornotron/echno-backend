package org.tornotron.echno_backend.vendor.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VendorBankAccountCreationDto {

    @NotBlank(message = "bank name is required")
    private String bankName;

    @NotBlank(message = "account number is required")
    private String accountNumber;

    private String ifscCode;
    private String accountHolderName;
    private String swift;

    private boolean isDefault = false;
}

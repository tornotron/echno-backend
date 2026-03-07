package org.tornotron.echno_backend.vendor.dto;

import lombok.Data;

@Data
public class VendorBankAccountDto {
    private Long id;
    private String bankName;
    private String accountNumber;
    private String ifscCode;
    private String accountHolderName;
    private String swift;
    private boolean isDefault;
}

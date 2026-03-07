package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.vendor.VendorBankAccount;
import org.tornotron.echno_backend.vendor.dto.VendorBankAccountDto;

@Component
public class VendorBankAccountsDtoConvertor {

    public static VendorBankAccountDto convertToDto(VendorBankAccount vendorBankAccount) {
        VendorBankAccountDto dto = new VendorBankAccountDto();
        dto.setId(vendorBankAccount.getId());
        dto.setAccountNumber(vendorBankAccount.getAccountNumber());
        dto.setBankName(vendorBankAccount.getBankName());
        dto.setIfscCode(vendorBankAccount.getIfscCode());
        dto.setAccountHolderName(vendorBankAccount.getAccountHolderName());
        dto.setSwift(vendorBankAccount.getSwift());
        dto.setDefault(vendorBankAccount.isDefault());
        return dto;
    }
}

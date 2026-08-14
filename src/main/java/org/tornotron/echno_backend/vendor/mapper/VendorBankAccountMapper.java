package org.tornotron.echno_backend.vendor.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.vendor.VendorBankAccount;
import org.tornotron.echno_backend.vendor.dto.VendorBankAccountDto;

/** Maps {@link VendorBankAccount} to its DTO. All fields by name. */
@Mapper(componentModel = "spring")
public interface VendorBankAccountMapper {
    VendorBankAccountDto toDto(VendorBankAccount account);
}

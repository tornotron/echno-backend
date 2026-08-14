package org.tornotron.echno_backend.vendor.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.vendor.VendorPaymentTerms;
import org.tornotron.echno_backend.vendor.dto.VendorPaymentTermsDto;

/** Maps {@link VendorPaymentTerms} to its DTO. All fields by name. */
@Mapper(componentModel = "spring")
public interface VendorPaymentTermsMapper {
    VendorPaymentTermsDto toDto(VendorPaymentTerms terms);
}

package org.tornotron.echno_backend.vendor.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.vendor.VendorTaxIdentifier;
import org.tornotron.echno_backend.vendor.dto.VendorTaxIdentifierDto;

/** Maps {@link VendorTaxIdentifier} to its DTO. All fields by name. */
@Mapper(componentModel = "spring")
public interface VendorTaxIdentifierMapper {
    VendorTaxIdentifierDto toDto(VendorTaxIdentifier taxIdentifier);
}

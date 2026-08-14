package org.tornotron.echno_backend.vendor.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.vendor.VendorContact;
import org.tornotron.echno_backend.vendor.dto.VendorContactDto;

/** Maps {@link VendorContact} to its DTO. All fields by name. */
@Mapper(componentModel = "spring")
public interface VendorContactMapper {
    VendorContactDto toDto(VendorContact contact);
}

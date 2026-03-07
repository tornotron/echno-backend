package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.vendor.VendorContact;
import org.tornotron.echno_backend.vendor.dto.VendorContactDto;

@Component
public class VendorContactDtoConvertor {

    public static VendorContactDto convertToDto(VendorContact vendorContact) {
        VendorContactDto dto = new VendorContactDto();
        dto.setId(vendorContact.getId());
        dto.setContactPerson(vendorContact.getContactPerson());
        dto.setEmail(vendorContact.getEmail());
        dto.setPhone(vendorContact.getPhone());
        dto.setAlternatePhone(vendorContact.getAlternatePhone());
        dto.setPrimary(vendorContact.isPrimary());
        return dto;
    }
}

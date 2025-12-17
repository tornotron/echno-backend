package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.dto.VendorDto;

@Component
public class VendorDtoConvertor {

    public static VendorDto convertToDto(Vendor vendor) {
        if (vendor == null) {
            return null;
        }

        VendorDto dto = new VendorDto();
        dto.setId(vendor.getId());
        dto.setVendorName(vendor.getVendorName());
        dto.setVendorAddress(vendor.getVendorAddress());
        dto.setVendorEmail(vendor.getVendorEmail());

        return dto;
    }
}

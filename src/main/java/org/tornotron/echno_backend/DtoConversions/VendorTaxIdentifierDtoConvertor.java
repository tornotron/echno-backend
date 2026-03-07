package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.vendor.VendorTaxIdentifier;
import org.tornotron.echno_backend.vendor.dto.VendorTaxIdentifierDto;

@Component
public class VendorTaxIdentifierDtoConvertor {

    public static VendorTaxIdentifierDto convertToDto(VendorTaxIdentifier vendorTaxIdentifier) {
        VendorTaxIdentifierDto dto = new VendorTaxIdentifierDto();
        dto.setId(vendorTaxIdentifier.getId());
        dto.setType(vendorTaxIdentifier.getType());
        dto.setValue(vendorTaxIdentifier.getValue());
        return dto;
    }
}

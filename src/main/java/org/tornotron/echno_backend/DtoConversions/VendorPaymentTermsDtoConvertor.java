package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.vendor.VendorPaymentTerms;
import org.tornotron.echno_backend.vendor.dto.VendorPaymentTermsDto;

@Component
public class VendorPaymentTermsDtoConvertor {

    public static VendorPaymentTermsDto convertToDto(VendorPaymentTerms vendorPaymentTerms) {
        if (vendorPaymentTerms == null) {
            return null;
        }
        VendorPaymentTermsDto dto = new VendorPaymentTermsDto();
        dto.setId(vendorPaymentTerms.getId());
        dto.setPaymentTerms(vendorPaymentTerms.getPaymentTerms());
        dto.setCreditLimit(vendorPaymentTerms.getCreditLimit());
        dto.setCreditDays(vendorPaymentTerms.getCreditDays());
        return dto;
    }
}

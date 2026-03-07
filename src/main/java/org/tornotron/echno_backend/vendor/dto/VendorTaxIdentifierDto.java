package org.tornotron.echno_backend.vendor.dto;

import lombok.Data;
import org.tornotron.echno_backend.vendor.enums.TaxIdentifierType;

@Data
public class VendorTaxIdentifierDto {
    private Long id;
    private TaxIdentifierType type;
    private String value;
}

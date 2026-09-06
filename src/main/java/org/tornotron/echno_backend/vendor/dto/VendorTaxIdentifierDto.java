package org.tornotron.echno_backend.vendor.dto;

import lombok.Data;
import org.tornotron.echno_backend.vendor.enums.TaxIdentifierType;

/**
 * A tax registration held by a vendor, as it is served. Every column behind this schema is
 * {@code NOT NULL}, so no field here admits null; see {@code ReviewedResponseSchemas}.
 */
@Data
public class VendorTaxIdentifierDto {
    private Long id;
    private TaxIdentifierType type;
    private String value;
}

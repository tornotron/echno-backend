package org.tornotron.echno_backend.vendor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * The payment terms agreed with a vendor, as they are served. A field without
 * {@code nullable = true} is one the schema behind it makes {@code NOT NULL}; see
 * {@code ReviewedResponseSchemas}.
 */
@Data
public class VendorPaymentTermsDto {

    private Long id;

    private String paymentTerms;

    @Schema(description = "Ceiling on what may be outstanding with this vendor. Null where no "
            + "limit was agreed, which is not the same as a limit of zero.", nullable = true)
    private BigDecimal creditLimit;

    @Schema(description = "Days allowed before payment falls due. Null where no period was "
            + "agreed, leaving the due date to whatever the invoice states.", nullable = true)
    private Integer creditDays;
}

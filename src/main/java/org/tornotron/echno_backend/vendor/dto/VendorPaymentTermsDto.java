package org.tornotron.echno_backend.vendor.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class VendorPaymentTermsDto {
    private Long id;
    private String paymentTerms;
    private BigDecimal creditLimit;
    private Integer creditDays;
}

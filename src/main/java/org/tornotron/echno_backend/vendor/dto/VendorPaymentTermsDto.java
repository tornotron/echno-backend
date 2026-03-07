package org.tornotron.echno_backend.vendor.dto;

import lombok.Data;
import org.tornotron.echno_backend.vendor.enums.PaymentTermsType;

import java.math.BigDecimal;

@Data
public class VendorPaymentTermsDto {
    private Long id;
    private PaymentTermsType paymentTerms;
    private BigDecimal creditLimit;
    private Integer creditDays;
}

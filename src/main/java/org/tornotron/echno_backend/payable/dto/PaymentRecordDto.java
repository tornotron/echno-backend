package org.tornotron.echno_backend.payable.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentRecordDto {

    @NotNull(message = "payment amount is required")
    @Positive(message = "payment amount must be positive")
    private BigDecimal paymentAmount;
}

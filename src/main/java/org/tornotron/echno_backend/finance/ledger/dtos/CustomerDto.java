package org.tornotron.echno_backend.finance.ledger.dtos;

import java.math.BigDecimal;
import java.util.UUID;

public record CustomerDto(
        UUID id,
        String code,
        String name,
        String gstin,
        String pan,
        String email,
        String phone,
        AddressDto billingAddress,
        BigDecimal creditLimit,
        Integer paymentTermsDays,
        boolean active
) {
}

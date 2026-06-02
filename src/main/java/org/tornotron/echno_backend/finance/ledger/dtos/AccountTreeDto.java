package org.tornotron.echno_backend.finance.ledger.dtos;

import org.tornotron.echno_backend.finance.ledger.AccountType;

import java.util.List;
import java.util.UUID;

public record AccountTreeDto(
        UUID id,
        String code,
        String name,
        AccountType type,
        boolean active,
        String description,
        boolean postable,
        List<AccountTreeDto> children
) {
}

package org.tornotron.echno_backend.finance.ledger.dtos;

import org.tornotron.echno_backend.finance.ledger.AccountType;

import java.util.UUID;

public record AccountDto(
        UUID id,
        String code,
        String name,
        AccountType type,
        UUID parentId,
        boolean active,
        String description
) {
}

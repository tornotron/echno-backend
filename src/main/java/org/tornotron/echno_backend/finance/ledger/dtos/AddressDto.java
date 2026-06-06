package org.tornotron.echno_backend.finance.ledger.dtos;

public record AddressDto(
        String line1,
        String line2,
        String city,
        String state,
        String stateCode,
        String postalCode,
        String country
) {
}

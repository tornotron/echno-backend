package org.tornotron.echno_backend.finance.ledger.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.finance.ledger.domain.Address;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.finance.ledger.dtos.AddressDto;
import org.tornotron.echno_backend.finance.ledger.dtos.CustomerDto;

@Mapper(componentModel = "spring")
public interface CustomerMapper {
    CustomerDto toDto(Customer customer);
    AddressDto toAddressDto(Address address);
    Address toAddress(AddressDto dto);
}

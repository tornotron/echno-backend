package org.tornotron.echno_backend.finance.ledger.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountDto;

import java.util.List;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    @Mapping(source = "parent.id", target = "parentId")
    AccountDto toDto(Account account);

    List<AccountDto> toDtos(List<Account> accounts);
}

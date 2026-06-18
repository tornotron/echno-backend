package org.tornotron.echno_backend.finance.bank.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.finance.bank.domain.CompanyBankAccount;
import org.tornotron.echno_backend.finance.bank.dtos.CompanyBankAccountDto;

import java.util.List;

@Mapper(componentModel = "spring")
public interface CompanyBankAccountMapper {

    @Mapping(source = "ledgerAccount.id", target = "ledgerAccountId")
    @Mapping(source = "ledgerAccount.code", target = "ledgerAccountCode")
    @Mapping(source = "ledgerAccount.name", target = "ledgerAccountName")
    @Mapping(source = "default", target = "isDefault")
    CompanyBankAccountDto toDto(CompanyBankAccount account);

    List<CompanyBankAccountDto> toDtos(List<CompanyBankAccount> accounts);
}

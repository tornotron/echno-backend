package org.tornotron.echno_backend.finance.ledger.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntryLine;
import org.tornotron.echno_backend.finance.ledger.dtos.JournalEntryDto;
import org.tornotron.echno_backend.finance.ledger.dtos.JournalEntryLineDto;

import java.util.List;

@Mapper(componentModel = "spring")
public interface JournalEntryMapper {

    @Mapping(target = "lines", source = "lines")
    JournalEntryDto toDto(JournalEntry entry);

    @Mapping(source = "account.id",   target = "accountId")
    @Mapping(source = "account.code", target = "accountCode")
    @Mapping(source = "account.name", target = "accountName")
    JournalEntryLineDto toLineDto(JournalEntryLine entryLine);

    List<JournalEntryLineDto> toLineDtos(List<JournalEntryLine> lines);
}

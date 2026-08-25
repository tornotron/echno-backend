package org.tornotron.echno_backend.expense.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.expense.Expense;
import org.tornotron.echno_backend.expense.dto.ExpenseDto;

/**
 * Maps {@link Expense} to its response DTO. The entity is flat; organization flattens
 * to its id.
 */
@Mapper(componentModel = "spring")
public interface ExpenseMapper {

    @Mapping(source = "organization.id", target = "organizationId")
    ExpenseDto toDto(Expense expense);
}

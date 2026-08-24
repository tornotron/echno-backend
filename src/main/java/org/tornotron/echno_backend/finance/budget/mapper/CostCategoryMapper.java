package org.tornotron.echno_backend.finance.budget.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;
import org.tornotron.echno_backend.finance.budget.dtos.CostCategoryDto;

import java.util.List;

@Mapper(componentModel = "spring")
public interface CostCategoryMapper {

    @Mapping(source = "expenseAccount.id", target = "expenseAccountId")
    @Mapping(source = "expenseAccount.code", target = "expenseAccountCode")
    CostCategoryDto toDto(CostCategory category);

    List<CostCategoryDto> toDtos(List<CostCategory> categories);
}

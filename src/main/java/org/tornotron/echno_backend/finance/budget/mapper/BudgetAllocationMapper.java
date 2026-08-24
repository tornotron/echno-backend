package org.tornotron.echno_backend.finance.budget.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.finance.budget.domain.BudgetAllocation;
import org.tornotron.echno_backend.finance.budget.dtos.BudgetAllocationDto;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BudgetAllocationMapper {

    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "costCategory.id", target = "costCategoryId")
    @Mapping(source = "costCategory.name", target = "costCategoryName")
    BudgetAllocationDto toDto(BudgetAllocation allocation);

    List<BudgetAllocationDto> toDtos(List<BudgetAllocation> allocations);
}

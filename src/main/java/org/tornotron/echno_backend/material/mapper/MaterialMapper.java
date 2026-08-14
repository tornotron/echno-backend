package org.tornotron.echno_backend.material.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.material.dto.MaterialWithStockDto;

/**
 * Maps {@link Material} to its DTOs. createdBy maps through {@link EmployeeMapper};
 * the aggregate current stock and stock value are pulled from {@link InventoryService}
 * in an {@code @AfterMapping} hook. The with-stock DTO takes an externally supplied
 * stock and value (the caller already has them).
 */
@Mapper(componentModel = "spring", uses = EmployeeMapper.class)
public abstract class MaterialMapper {

    @Autowired
    protected InventoryService inventoryService;

    @Mapping(target = "currentStock", ignore = true) // filled in fillStock
    @Mapping(target = "stockValue", ignore = true)   // filled in fillStock
    public abstract MaterialDto toDto(Material material);

    @AfterMapping
    protected void fillStock(Material material, @MappingTarget MaterialDto dto) {
        dto.setCurrentStock(inventoryService.getAggregateStock(material.getId()));
        dto.setStockValue(inventoryService.getAggregateStockValue(material.getId()));
    }

    @Mapping(target = "currentStock", expression = "java(currentStock != null ? currentStock : 0.0)")
    public abstract MaterialWithStockDto toWithStockDto(Material material, Double currentStock, java.math.BigDecimal stockValue);
}

package org.tornotron.echno_backend.material.mapper;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.material.dto.MaterialWithStockDto;

import java.math.BigDecimal;

/**
 * Maps {@link Material} to its DTOs. createdBy maps through {@link EmployeeMapper}; the
 * aggregate current stock and stock value are read from the {@link MaterialStockLookup} the
 * caller hands in.
 *
 * <p>The stock used to be fetched here, from {@link InventoryService}, in an
 * {@code @AfterMapping} hook. That cost two aggregate queries for every material mapped and
 * nothing at the call site showed it, so a page of fifty materials cost a hundred extra reads
 * and an indent paid the same again on every line. The caller now reads the whole page's stock
 * in one grouped query and passes it down, which is the shape {@link #toWithStockDto} has always
 * had. The lookup travels as a MapStruct {@code @Context}, so the mappers that nest this one
 * (an indent line carries a material) pass the same instance through rather than each fetching
 * their own.
 */
@Mapper(componentModel = "spring", uses = EmployeeMapper.class)
public interface MaterialMapper {

    /**
     * Converts a material, taking its stock figures from the supplied lookup.
     *
     * @param material The material to convert.
     * @param stock The stock read for the whole set of materials being mapped. A material absent
     *              from it reads as zero, which is what the per-material query returned.
     * @return The material DTO.
     */
    @Mapping(target = "currentStock", expression = "java(stock.currentStockOf(material.getId()))")
    @Mapping(target = "stockValue", expression = "java(stock.stockValueOf(material.getId()))")
    MaterialDto toDto(Material material, @Context MaterialStockLookup stock);

    @Mapping(target = "currentStock", expression = "java(currentStock != null ? currentStock : 0.0)")
    MaterialWithStockDto toWithStockDto(Material material, Double currentStock, BigDecimal stockValue);
}

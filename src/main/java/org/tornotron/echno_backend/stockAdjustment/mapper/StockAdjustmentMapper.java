package org.tornotron.echno_backend.stockAdjustment.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustment;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentLineItem;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentDto;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentLineItemDto;

/**
 * Maps {@link StockAdjustment} and its line items to their DTOs. Location, project,
 * organization and material references flatten to id (+ name).
 */
@Mapper(componentModel = "spring")
public interface StockAdjustmentMapper {

    @Mapping(source = "location.id", target = "locationId")
    @Mapping(source = "location.locationName", target = "locationName")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    @Mapping(source = "organization.id", target = "organizationId")
    StockAdjustmentDto toDto(StockAdjustment stockAdjustment);

    @Mapping(source = "material.id", target = "materialId")
    @Mapping(source = "material.materialName", target = "materialName")
    @Mapping(source = "location.id", target = "locationId")
    @Mapping(source = "location.locationName", target = "locationName")
    StockAdjustmentLineItemDto toLineItemDto(StockAdjustmentLineItem item);
}

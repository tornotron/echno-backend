package org.tornotron.echno_backend.stockAdjustment.mapper;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustment;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentLineItem;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentDto;
import org.tornotron.echno_backend.stockAdjustment.dto.StockAdjustmentLineItemDto;
import org.tornotron.echno_backend.user.UserNameLookup;

/**
 * Maps {@link StockAdjustment} and its line items to their DTOs. Location, project,
 * organization and material references flatten to id (+ name).
 *
 * <p>The workflow stamps flatten the same way, but their names cannot come from the adjustment:
 * it holds only the user id. They are read from the {@link UserNameLookup} the caller hands in,
 * which is the shape {@code MaterialMapper.toWithStockDto} established and
 * {@code MapperDatabaseAccessTest} enforces. Resolving them here instead would cost one query per
 * stamp per row, and the call site would show nothing.
 *
 * <p>{@code physicalCountBy} is deliberately left alone. It is set from the creation payload and
 * holds an employee id, not a user id, so this lookup would name the wrong person for it. That
 * confusion is precisely what put a stranger's name against an approval on the web app.
 */
@Mapper(componentModel = "spring")
public interface StockAdjustmentMapper {

    /**
     * Converts an adjustment, taking its workflow stamp names from the supplied lookup.
     *
     * @param stockAdjustment The adjustment to convert.
     * @param names The names read for every user id on the set of adjustments being mapped.
     * @return The adjustment DTO.
     */
    @Mapping(source = "location.id", target = "locationId")
    @Mapping(source = "location.locationName", target = "locationName")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    @Mapping(source = "organization.id", target = "organizationId")
    @Mapping(target = "submittedByName",
            expression = "java(names.nameOf(stockAdjustment.getSubmittedBy()))")
    @Mapping(target = "approvedByName",
            expression = "java(names.nameOf(stockAdjustment.getApprovedBy()))")
    @Mapping(target = "rejectedByName",
            expression = "java(names.nameOf(stockAdjustment.getRejectedBy()))")
    @Mapping(target = "processedByName",
            expression = "java(names.nameOf(stockAdjustment.getProcessedBy()))")
    StockAdjustmentDto toDto(StockAdjustment stockAdjustment, @Context UserNameLookup names);

    @Mapping(source = "material.id", target = "materialId")
    @Mapping(source = "material.materialName", target = "materialName")
    @Mapping(source = "location.id", target = "locationId")
    @Mapping(source = "location.locationName", target = "locationName")
    StockAdjustmentLineItemDto toLineItemDto(StockAdjustmentLineItem item);
}

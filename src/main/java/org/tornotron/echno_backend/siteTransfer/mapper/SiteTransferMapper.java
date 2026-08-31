package org.tornotron.echno_backend.siteTransfer.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.siteTransfer.SiteTransfer;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;

/**
 * Maps {@link SiteTransfer} to its DTO. The sending person maps through
 * {@link EmployeeMapper}; the sending/receiving project and storage-location
 * associations flatten to id + name; the item lines map through {@link #toItemDto}.
 *
 * The previous converter left {@code items} null when the transfer had none (it only
 * set the list when non-empty), so {@link #nullifyEmptyItems} restores that: MapStruct
 * would otherwise emit an empty list.
 */
@Mapper(componentModel = "spring", uses = EmployeeMapper.class)
public interface SiteTransferMapper {

    @Mapping(source = "sendingProject.id", target = "sendingProjectId")
    @Mapping(source = "sendingProject.projectName", target = "sendingProjectName")
    @Mapping(source = "sendingStorageLocation.id", target = "sendingStorageLocationId")
    @Mapping(source = "sendingStorageLocation.locationName", target = "sendingStorageLocationName")
    @Mapping(source = "receivingProject.id", target = "receivingProjectId")
    @Mapping(source = "receivingProject.projectName", target = "receivingProjectName")
    @Mapping(source = "receivingStorageLocation.id", target = "receivingStorageLocationId")
    @Mapping(source = "receivingStorageLocation.locationName", target = "receivingStorageLocationName")
    SiteTransferDto toDto(SiteTransfer transfer);

    @Mapping(source = "material.id", target = "materialId")
    @Mapping(source = "material.materialName", target = "materialName")
    @Mapping(target = "inTransitQuantity", ignore = true)
    SiteTransferItemDto toItemDto(SiteTransferItem item);

    /**
     * Fills in how much of a line is neither at the sending site nor recorded as having reached
     * the receiving one.
     *
     * <p>Derived rather than stored, so it cannot drift from the two quantities it is the
     * difference of. A line nobody has confirmed yet carries a null received quantity, and the
     * whole sent quantity is in transit. It is floored at zero because an acknowledged
     * over-receipt would otherwise report a negative amount on a lorry; that a receipt exceeded
     * what was sent is visible from the two quantities themselves.
     */
    @AfterMapping
    default void deriveInTransitQuantity(SiteTransferItem item, @MappingTarget SiteTransferItemDto dto) {
        int sent = item.getSentQuantity() != null ? item.getSentQuantity() : 0;
        int received = item.getReceivedQuantity() != null ? item.getReceivedQuantity() : 0;
        dto.setInTransitQuantity(Math.max(0, sent - received));
    }

    @AfterMapping
    default void nullifyEmptyItems(@MappingTarget SiteTransferDto dto) {
        if (dto.getItems() != null && dto.getItems().isEmpty()) {
            dto.setItems(null);
        }
    }
}

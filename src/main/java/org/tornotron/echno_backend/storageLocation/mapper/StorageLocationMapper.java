package org.tornotron.echno_backend.storageLocation.mapper;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.inventoryTransaction.StorageLocationItemCounts;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;

/**
 * Maps {@link StorageLocation} to its DTO. Project is flattened to id + name; the
 * distinct-materials count is read from the {@link StorageLocationItemCounts} the caller hands in.
 *
 * <p>The count used to be issued here, one {@code COUNT DISTINCT} per row, from an
 * {@code @AfterMapping} hook holding the stock repository. Four listing paths went through it, so
 * every location on a page cost a query that the listing code gave no sign of. The caller now
 * counts the whole page in one grouped read.
 */
@Mapper(componentModel = "spring")
public interface StorageLocationMapper {

    /**
     * Converts a storage location, taking its item count from the supplied lookup.
     *
     * @param storageLocation The location to convert.
     * @param itemCounts The counts read for the whole set of locations being mapped. A location
     *                   absent from it reads as zero, which is what the per-row count returned.
     * @return The storage location DTO.
     */
    @Mapping(source = "storageLocation.project.id", target = "projectId")
    @Mapping(source = "storageLocation.project.projectName", target = "projectName")
    @Mapping(target = "storageItemsCount",
            expression = "java(itemCounts.itemCountOf(storageLocation.getId()))")
    StorageLocationDto toDto(StorageLocation storageLocation, @Context StorageLocationItemCounts itemCounts);
}

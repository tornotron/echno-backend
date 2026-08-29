package org.tornotron.echno_backend.inventoryTransaction;

/**
 * How many distinct materials are stocked at one storage location, as returned by the grouped
 * read in {@link CurrentStockRepository#countDistinctMaterialsByStorageLocationIds}.
 *
 * <p>As with {@link MaterialStockTotals}, a location holding nothing produces no row;
 * {@link StorageLocationItemCounts} supplies the zero.
 *
 * @param storageLocationId The storage location this count belongs to.
 * @param itemCount The number of distinct materials with a stock row at that location.
 */
public record StorageLocationItemCount(Long storageLocationId, Long itemCount) {
}

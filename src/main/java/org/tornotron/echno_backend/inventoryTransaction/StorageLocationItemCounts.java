package org.tornotron.echno_backend.inventoryTransaction;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The distinct-material count for a whole set of storage locations, read once and handed to the
 * mapper.
 *
 * <p>The counterpart to {@link MaterialStockLookup}, for the same reason: counting the materials
 * at a location inside the conversion cost one {@code COUNT DISTINCT} per row across four listing
 * paths, and nothing at the call site said so. The caller now asks
 * {@link InventoryService#itemCountsAt} for every location on the page at once.
 *
 * <p>A location holding nothing is absent from the grouped result and reads as zero, which is what
 * the per-row {@code COUNT} returned.
 */
public final class StorageLocationItemCounts {

    private static final StorageLocationItemCounts EMPTY = new StorageLocationItemCounts(Map.of());

    private final Map<Long, Long> byStorageLocationId;

    private StorageLocationItemCounts(Map<Long, Long> byStorageLocationId) {
        this.byStorageLocationId = byStorageLocationId;
    }

    /**
     * A lookup holding nothing, so every location reads as zero items.
     *
     * @return The empty lookup.
     */
    public static StorageLocationItemCounts none() {
        return EMPTY;
    }

    /**
     * Builds a lookup from the rows of a grouped read.
     *
     * @param counts The per-location counts, at most one row per location.
     * @return A lookup over those counts.
     */
    public static StorageLocationItemCounts of(Collection<StorageLocationItemCount> counts) {
        if (counts == null || counts.isEmpty()) {
            return EMPTY;
        }
        return new StorageLocationItemCounts(counts.stream()
                .filter(row -> row.storageLocationId() != null)
                .collect(Collectors.toMap(StorageLocationItemCount::storageLocationId,
                        row -> row.itemCount() == null ? 0L : row.itemCount(),
                        (first, second) -> first)));
    }

    /**
     * How many distinct materials are stocked at a location.
     *
     * @param storageLocationId The location to read, which may be null for an entity not yet persisted.
     * @return The count, or zero where the location holds nothing.
     */
    public Long itemCountOf(Long storageLocationId) {
        return byStorageLocationId.getOrDefault(storageLocationId, 0L);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StorageLocationItemCounts counts
                && byStorageLocationId.equals(counts.byStorageLocationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(byStorageLocationId);
    }

    @Override
    public String toString() {
        return "StorageLocationItemCounts" + byStorageLocationId.keySet();
    }
}

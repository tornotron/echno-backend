package org.tornotron.echno_backend.inventoryTransaction;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The aggregate stock figures for a whole set of materials, read once and handed to the mapper.
 *
 * <p>This is the shape that keeps stock out of the conversion path. A mapper that has to ask for
 * a material's stock costs two queries per row and shows nothing at the call site, so a page of
 * fifty materials quietly costs a hundred reads. The caller instead collects the material ids it
 * is about to map, asks {@link InventoryService#aggregateStockFor} for all of them in one grouped
 * query, and passes the answer down as a MapStruct {@code @Context}. Every nested mapper on the
 * way (an indent line carries a material, an indent carries its lines) receives the same instance,
 * so the depth of the DTO no longer multiplies the query count.
 *
 * <p>A material with no stock row anywhere is absent from the grouped result. The two accessors
 * return zero for it, which is what the per-material {@code COALESCE(SUM(...), 0)} reads returned
 * and what the response has always carried.
 */
public final class MaterialStockLookup {

    private static final MaterialStockLookup EMPTY = new MaterialStockLookup(Map.of());

    private final Map<Long, MaterialStockTotals> byMaterialId;

    private MaterialStockLookup(Map<Long, MaterialStockTotals> byMaterialId) {
        this.byMaterialId = byMaterialId;
    }

    /**
     * A lookup holding nothing, so every material reads as zero stock.
     *
     * <p>For the paths that map a material which cannot have stock yet, and for tests. It is not
     * a fallback for a caller that forgot to fetch: that would silently zero a real balance.
     *
     * @return The empty lookup.
     */
    public static MaterialStockLookup none() {
        return EMPTY;
    }

    /**
     * Builds a lookup from the rows of a grouped read.
     *
     * @param totals The per-material totals, at most one row per material.
     * @return A lookup over those totals.
     */
    public static MaterialStockLookup of(Collection<MaterialStockTotals> totals) {
        if (totals == null || totals.isEmpty()) {
            return EMPTY;
        }
        return new MaterialStockLookup(totals.stream()
                .filter(row -> row.materialId() != null)
                .collect(Collectors.toMap(MaterialStockTotals::materialId, Function.identity(),
                        (first, second) -> first)));
    }

    /**
     * The quantity on hand for a material.
     *
     * @param materialId The material to read, which may be null for an entity not yet persisted.
     * @return The quantity on hand, or zero where the material holds no stock.
     */
    public Double currentStockOf(Long materialId) {
        MaterialStockTotals totals = byMaterialId.get(materialId);
        return totals == null || totals.currentStock() == null ? 0.0 : totals.currentStock();
    }

    /**
     * The value of the stock on hand for a material.
     *
     * @param materialId The material to read, which may be null for an entity not yet persisted.
     * @return The stock value, or zero where the material holds no stock.
     */
    public BigDecimal stockValueOf(Long materialId) {
        MaterialStockTotals totals = byMaterialId.get(materialId);
        return totals == null || totals.stockValue() == null ? BigDecimal.ZERO : totals.stockValue();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MaterialStockLookup lookup && byMaterialId.equals(lookup.byMaterialId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(byMaterialId);
    }

    @Override
    public String toString() {
        return "MaterialStockLookup" + byMaterialId.keySet();
    }
}

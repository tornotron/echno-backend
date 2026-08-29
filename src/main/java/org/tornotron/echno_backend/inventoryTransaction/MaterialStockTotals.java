package org.tornotron.echno_backend.inventoryTransaction;

import java.math.BigDecimal;

/**
 * One material's aggregate quantity and value on hand, as returned by the grouped read in
 * {@link CurrentStockRepository#sumStockByMaterialIds}.
 *
 * <p>A row exists only for a material that has at least one {@code CurrentStock} row, so a
 * material with no stock anywhere is absent from the result rather than present with a zero.
 * {@link MaterialStockLookup} is what turns that absence back into the zero the single-material
 * reads have always returned.
 *
 * @param materialId The material these totals belong to.
 * @param currentStock The quantity on hand across every project and storage location.
 * @param stockValue That quantity valued at its running weighted-average unit cost.
 */
public record MaterialStockTotals(Long materialId, Double currentStock, BigDecimal stockValue) {
}

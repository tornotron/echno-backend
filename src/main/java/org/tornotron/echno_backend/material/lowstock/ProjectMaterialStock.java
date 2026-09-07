package org.tornotron.echno_backend.material.lowstock;

/**
 * What one material's position on one project is, for a material the sweep already knows about.
 *
 * <p>The counterpart to {@link LowStockRow}, and needed because that one cannot answer this
 * question. The low-stock query returns what is low; deciding whether a material that was
 * reported low has recovered needs the level and the quantity for a material the low-stock query
 * has stopped returning, which is precisely the row it no longer produces.
 *
 * @param materialId The material.
 * @param reorderLevel The material's own reorder level, or null if nobody has set one. Null is
 *         not zero: it means no line has been drawn, so there is nothing to be below.
 * @param currentStock What the project holds across all its storage locations. Zero, never null:
 *         a material with no stock row on the project holds nothing there.
 */
public record ProjectMaterialStock(Long materialId, Double reorderLevel, Double currentStock) {
}

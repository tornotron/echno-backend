package org.tornotron.echno_backend.material.lowstock;

/**
 * One material found at or below its reorder level, as the repository reads it.
 *
 * <p>A flat projection rather than the {@code Material} entity, because the only thing wanted
 * from the material is five scalars and the quantity beside them is an aggregate the entity
 * does not carry. Reading the entity would load the catalogue row and then still need the
 * grouped stock read, and the association graph hanging off it is of no use to a caller who
 * asked what is running out.
 *
 * <p>The threshold is the one that was actually applied, not necessarily the material's own:
 * at storage-location scope a {@code MaterialLocationThreshold} override takes its place, and
 * the same goes for the minimum order quantity. Which one it was is not recorded here, because
 * a caller acting on the row wants the level that decided the row, and the material's global
 * level is one read away for anyone who wants both.
 *
 * @param materialId The material at or below its level.
 * @param sku Its stock keeping unit code, null when the material has none.
 * @param materialName Its name.
 * @param unit Its unit of measure.
 * @param moq The minimum order quantity in force at the scope that was asked about.
 * @param reorderLevel The reorder level in force at that scope: what {@code currentStock} was
 *         compared against.
 * @param currentStock The quantity on hand at that scope. Zero, never null: a material with no
 *         stock row at all reads as nothing on hand rather than as unknown.
 */
public record LowStockRow(
        Long materialId,
        String sku,
        String materialName,
        String unit,
        Double moq,
        Double reorderLevel,
        Double currentStock) {
}

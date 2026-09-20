package org.tornotron.echno_backend.indentItem;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The line counts for a whole page of indents, read once and handed to the mapper.
 *
 * <p>A list of indents wants to say how many lines each one has and how many are already on a
 * purchase order. Reaching that through {@code indent.getItems()} loads every line of every
 * indent on the page, and each line carries a material, so the page also pays for the material
 * graph and its stock lookup. Both counts come from one grouped read instead and travel down as
 * a MapStruct {@code @Context},
 * the shape {@link org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup}
 * established.
 */
public final class IndentItemCountLookup {

    private static final IndentItemCountLookup EMPTY = new IndentItemCountLookup(Map.of());

    private final Map<Long, IndentItemCount> byIndentId;

    private IndentItemCountLookup(Map<Long, IndentItemCount> byIndentId) {
        this.byIndentId = byIndentId;
    }

    /**
     * A lookup holding nothing, so every indent reads as having no lines.
     *
     * <p>For an indent that cannot have lines yet, and for tests.
     *
     * @return The empty lookup.
     */
    public static IndentItemCountLookup none() {
        return EMPTY;
    }

    /**
     * Builds a lookup from the rows of a grouped read.
     *
     * @param counts The per-indent counts, at most one row per indent.
     * @return A lookup over those counts.
     */
    public static IndentItemCountLookup of(Collection<IndentItemCount> counts) {
        if (counts == null || counts.isEmpty()) {
            return EMPTY;
        }
        return new IndentItemCountLookup(counts.stream()
                .filter(row -> row.indentId() != null)
                .collect(Collectors.toMap(IndentItemCount::indentId, Function.identity(),
                        (first, second) -> first)));
    }

    /**
     * How many lines an indent has.
     *
     * @param indentId The indent to read, which may be null for an entity not yet persisted.
     * @return The line count, or zero where the indent has none.
     */
    public long itemCountOf(Long indentId) {
        IndentItemCount row = byIndentId.get(indentId);
        return row == null ? 0L : row.itemCount();
    }

    /**
     * How many of an indent's lines have been converted into a purchase order.
     *
     * @param indentId The indent to read.
     * @return The count, zero where the indent has no lines or none converted.
     */
    public long convertedItemCountOf(Long indentId) {
        IndentItemCount row = byIndentId.get(indentId);
        return row == null ? 0L : row.convertedItemCount();
    }

    /**
     * Whether the lookup holds no rows at all.
     *
     * @return {@code true} when every indent reads as having no lines.
     */
    public boolean isEmpty() {
        return byIndentId.isEmpty();
    }
}

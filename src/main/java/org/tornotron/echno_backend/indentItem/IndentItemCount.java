package org.tornotron.echno_backend.indentItem;

/**
 * How many item lines one indent has, as returned by the grouped read in
 * {@link IndentItemRepository#countItemsByIndentIds}.
 *
 * <p>An indent with no lines produces no group, so it is absent from the result rather than
 * present with a zero. {@link IndentItemCountLookup} turns that absence back into zero.
 *
 * @param indentId The indent this count belongs to.
 * @param itemCount How many lines it has.
 */
public record IndentItemCount(Long indentId, long itemCount) {
}

package org.tornotron.echno_backend.stockAdjustment.enums;

/**
 * The kind of document a stock adjustment was raised to answer.
 *
 * <p>An adjustment usually stands on its own: somebody counted a location and the count
 * disagreed with the balance. Some are raised for a named reason instead, and then the document
 * that caused them is part of what the adjustment means. A closed set is what makes that
 * reference safe to store: every value here has a resolver that loads the named document within
 * the caller's organization before the reference is written, so an id that names nothing, or
 * names something belonging to somebody else, is refused at the door rather than saved as a
 * pointer into another tenant's data.
 *
 * <p>Adding a value means adding its resolver. That is deliberate: a type with no resolver would
 * be an id taken on trust, which is the shape of the bug this enum exists to prevent.
 */
public enum StockAdjustmentSourceType {

    /**
     * A site transfer whose receipt left an open variance.
     *
     * <p>A transfer received short leaves the sending site down the full sent quantity and the
     * receiving site up only what arrived. The transfer writes no loss movement for the
     * difference on purpose, because a loss written automatically is a stock correction nobody
     * authorised. The adjustment that decides what became of the difference names the transfer
     * here, so the correction and the transfer that caused it can be read from either end.
     */
    SITE_TRANSFER
}

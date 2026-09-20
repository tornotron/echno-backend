package org.tornotron.echno_backend.documentReversal.enums;

/**
 * The kinds of document a reversal may be raised against.
 *
 * <p>A closed set, for the reason {@code StockAdjustmentSourceType} is one: every value here has
 * a resolver in {@code DocumentReversalService} that loads the named document within the caller's
 * organization before anything is written, so an id that names nothing, or names another
 * tenant's document, is refused at the door. Adding a value means adding its resolver, its
 * creator lookup and its blocker check.
 */
public enum ReversibleDocumentType {
    /** A site transfer: its outbound leg, and its inbound leg where one was written, are undone. */
    SITE_TRANSFER,
    /** A purchase order: it writes no stock, so its reversal is a status change and a link. */
    PURCHASE_ORDER,
    /** A goods received note: the receipt is undone and the order's received quantities fall back. */
    GOODS_RECEIVED_NOTE
}

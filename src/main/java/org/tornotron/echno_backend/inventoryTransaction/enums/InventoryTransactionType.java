package org.tornotron.echno_backend.inventoryTransaction.enums;

/**
 * The reason a stock movement was recorded. Each type carries the direction its
 * movement has on stock ({@link StockEffect}), so the sign is defined in one place
 * rather than inferred at every call site.
 *
 * <p>Note on how stock is actually applied today: callers of
 * {@code InventoryService.updateCurrentStock} pass an already-signed
 * {@code quantityChanged} (negative for outbound movements such as USE and
 * TRANSFER_OUT, positive for inbound ones), and the current-stock update derives
 * its increase/decrease purely from that sign. The transaction type is stored as a
 * label and is not consulted when the balance is changed. {@code stockEffect} is
 * therefore authoritative metadata about each reason's direction, available for
 * validation, reporting and future producers, but it is intentionally not wired
 * into the existing sign application, which stays sign-driven and unchanged.</p>
 */
public enum InventoryTransactionType {

    OPENING_BALANCE(StockEffect.INCREASE),
    GRN(StockEffect.INCREASE),
    PURCHASE_RETURN(StockEffect.DECREASE),
    USE(StockEffect.DECREASE),
    PRODUCTION_CONSUME(StockEffect.DECREASE),
    PRODUCTION_OUTPUT(StockEffect.INCREASE),
    SCRAP(StockEffect.DECREASE),
    DAMAGE(StockEffect.DECREASE),
    EXPIRE(StockEffect.DECREASE),
    LOSS(StockEffect.DECREASE),
    TRANSFER_OUT(StockEffect.DECREASE),
    TRANSFER_IN(StockEffect.INCREASE),
    CUSTOMER_RETURN(StockEffect.INCREASE),
    STOCK_TAKE_GAIN(StockEffect.INCREASE),
    STOCK_TAKE_LOSS(StockEffect.DECREASE),
    WRITE_OFF(StockEffect.DECREASE),
    ADJUST(StockEffect.EITHER);

    private final StockEffect stockEffect;

    InventoryTransactionType(StockEffect stockEffect) {
        this.stockEffect = stockEffect;
    }

    public StockEffect getStockEffect() {
        return stockEffect;
    }

    /**
     * The direction a transaction type moves stock in.
     * {@code EITHER} means the sign is not fixed by the type and comes from the
     * signed quantity supplied by the caller (as ADJUST does today).
     */
    public enum StockEffect {
        INCREASE,
        DECREASE,
        EITHER
    }
}

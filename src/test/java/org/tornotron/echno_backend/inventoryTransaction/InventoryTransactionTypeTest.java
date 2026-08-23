package org.tornotron.echno_backend.inventoryTransaction;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType.StockEffect;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that every {@link InventoryTransactionType} constant carries the stock
 * effect defined for it in issue #258, and that the full set of reasons is present.
 */
class InventoryTransactionTypeTest {

    @Test
    void everyTypeMapsToExpectedStockEffect() {
        Map<InventoryTransactionType, StockEffect> expected =
                new EnumMap<>(InventoryTransactionType.class);
        expected.put(InventoryTransactionType.OPENING_BALANCE, StockEffect.INCREASE);
        expected.put(InventoryTransactionType.GRN, StockEffect.INCREASE);
        expected.put(InventoryTransactionType.PURCHASE_RETURN, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.USE, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.PRODUCTION_CONSUME, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.PRODUCTION_OUTPUT, StockEffect.INCREASE);
        expected.put(InventoryTransactionType.SCRAP, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.DAMAGE, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.EXPIRE, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.LOSS, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.TRANSFER_OUT, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.TRANSFER_IN, StockEffect.INCREASE);
        expected.put(InventoryTransactionType.CUSTOMER_RETURN, StockEffect.INCREASE);
        expected.put(InventoryTransactionType.STOCK_TAKE_GAIN, StockEffect.INCREASE);
        expected.put(InventoryTransactionType.STOCK_TAKE_LOSS, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.WRITE_OFF, StockEffect.DECREASE);
        expected.put(InventoryTransactionType.ADJUST, StockEffect.EITHER);

        // Every declared constant must have an expectation, so a newly added value
        // without a defined effect fails the test rather than passing silently.
        assertEquals(expected.size(), InventoryTransactionType.values().length,
                "Every transaction type must have an expected stock effect asserted");

        for (Map.Entry<InventoryTransactionType, StockEffect> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), entry.getKey().getStockEffect(),
                    "Unexpected stock effect for " + entry.getKey());
        }
    }

    @Test
    void adjustIsTheOnlyEitherEffect() {
        for (InventoryTransactionType type : InventoryTransactionType.values()) {
            assertNotNull(type.getStockEffect(), type + " must declare a stock effect");
            if (type.getStockEffect() == StockEffect.EITHER) {
                assertEquals(InventoryTransactionType.ADJUST, type,
                        "ADJUST is the only type whose sign comes from the signed quantity");
            }
        }
    }
}

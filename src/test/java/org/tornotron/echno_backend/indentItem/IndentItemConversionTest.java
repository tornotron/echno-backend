package org.tornotron.echno_backend.indentItem;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an indent line records when it is put on a purchase order (#861): the PO number and the
 * quantity ordered, both of which used to stay empty while only the converted flag was set.
 */
class IndentItemConversionTest {

    private static IndentItem line(Integer typedOrderedQuantity) {
        IndentItem item = new IndentItem();
        item.setRequestedQuantity(10);
        item.setOrderedQuantity(typedOrderedQuantity);
        item.setConvertedToPurchaseOrder(false);
        return item;
    }

    @Test
    void firstConversion_setsTheNumberAndTheQuantity() {
        IndentItem item = line(null);

        item.recordConversion("PO-2026-000007", 4);

        assertThat(item.getConvertedToPurchaseOrder()).isTrue();
        assertThat(item.getLinkedPurchaseOrderNumber()).isEqualTo("PO-2026-000007");
        assertThat(item.getOrderedQuantity()).isEqualTo(4);
    }

    @Test
    void firstConversion_replacesAQuantityTypedBeforeOrdering() {
        IndentItem item = line(9);

        item.recordConversion("PO-2026-000007", 4);

        assertThat(item.getOrderedQuantity()).isEqualTo(4);
    }

    @Test
    void aPartlyOrderedLine_goingOnASecondOrder_addsItsQuantityAndNumber() {
        IndentItem item = line(null);

        item.recordConversion("PO-2026-000007", 4);
        item.recordConversion("PO-2026-000008", 6);

        assertThat(item.getOrderedQuantity()).isEqualTo(10);
        assertThat(item.getLinkedPurchaseOrderNumber()).isEqualTo("PO-2026-000007, PO-2026-000008");
    }

    @Test
    void theSameOrderTwice_isListedOnce() {
        IndentItem item = line(null);

        item.recordConversion("PO-2026-000007", 4);
        item.recordConversion("PO-2026-000007", 1);

        assertThat(item.getLinkedPurchaseOrderNumber()).isEqualTo("PO-2026-000007");
        assertThat(item.getOrderedQuantity()).isEqualTo(5);
    }

    @Test
    void aListThatWouldOverflowTheColumn_keepsWhatItHas() {
        IndentItem item = line(null);
        String longNumber = "PO-" + "9".repeat(90);

        item.recordConversion(longNumber, 1);
        item.recordConversion("PO-2026-000008", 1);

        assertThat(item.getLinkedPurchaseOrderNumber()).isEqualTo(longNumber);
        assertThat(item.getOrderedQuantity()).isEqualTo(2);
    }
}

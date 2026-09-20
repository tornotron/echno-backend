package org.tornotron.echno_backend.purchaseOrder.enums;

public enum PurchaseOrderStatus {
    DRAFT,
    APPROVED,
    SENT_TO_VENDOR,
    PARTIALLY_RECEIVED,
    FULLY_RECEIVED,
    CANCELLED,
    /**
     * Undone under an approved reversal request, before anything was received against it. The
     * order stays on the record and {@code reversalId} names the request. Terminal, and not
     * settable from a status payload; only {@code DocumentReversalService} writes it.
     */
    REVERSED
}

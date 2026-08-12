package org.tornotron.echno_backend.finance.construction;

/**
 * Processing status of a construction payment voucher. Named with the "Voucher"
 * qualifier because {@link ConstructionPaymentStatus} is already taken by the
 * construction invoice's payment-progress field (UNPAID / PARTIALLY_PAID / PAID).
 * In this increment the status is set directly on create and update; no ledger
 * journal entry is posted on any transition.
 */
public enum ConstructionPaymentVoucherStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REFUNDED
}

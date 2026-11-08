package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupees to two places, and to the paise a provider counts in. */
public final class BillPaymentAmounts {

    private BillPaymentAmounts() {
    }

    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static long toPaise(BigDecimal amount) {
        return money(amount).movePointRight(2).longValueExact();
    }
}

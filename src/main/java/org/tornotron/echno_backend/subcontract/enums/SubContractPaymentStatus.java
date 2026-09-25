package org.tornotron.echno_backend.subcontract.enums;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How far payment on a subcontract has got. Derived on read from the contract value, the amount
 * paid and the end date; nothing stores it.
 *
 * <p>The wire values are those of the web app's {@code ContractPaymentStatus}.
 */
public enum SubContractPaymentStatus {
    NOT_STARTED("notStarted"),
    IN_PROGRESS("inProgress"),
    FULLY_PAID("fullyPaid"),
    OVERDUE("overdue");

    private final String value;

    SubContractPaymentStatus(String value) {
        this.value = value;
    }

    /** The string sent on the wire. */
    public String value() {
        return value;
    }

    /**
     * Derives the payment status.
     *
     * <ul>
     *   <li>fully paid once the amount paid reaches a positive contract value;</li>
     *   <li>overdue when the end date has passed and it is not fully paid;</li>
     *   <li>not started while nothing has been paid;</li>
     *   <li>in progress otherwise.</li>
     * </ul>
     *
     * @param contractValue the contract value, or null when not recorded
     * @param totalPaid     the amount paid so far, or null for none
     * @param endDate       the contract end date, or null when open ended
     * @param today         the date to judge overdue against
     */
    public static SubContractPaymentStatus derive(
            BigDecimal contractValue, BigDecimal totalPaid, LocalDate endDate, LocalDate today) {
        BigDecimal paid = totalPaid != null ? totalPaid : BigDecimal.ZERO;
        if (contractValue != null && contractValue.signum() > 0 && paid.compareTo(contractValue) >= 0) {
            return FULLY_PAID;
        }
        if (endDate != null && endDate.isBefore(today)) {
            return OVERDUE;
        }
        return paid.signum() > 0 ? IN_PROGRESS : NOT_STARTED;
    }
}

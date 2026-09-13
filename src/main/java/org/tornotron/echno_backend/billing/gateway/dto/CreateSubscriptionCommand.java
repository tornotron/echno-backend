package org.tornotron.echno_backend.billing.gateway.dto;

import org.tornotron.echno_backend.billing.enums.BillingPeriod;

import java.time.Instant;

/**
 * Asks the provider to create a subscription for an organization on an internal plan.
 *
 * @param organizationId The organization being billed.
 * @param planCode The internal {@code Plan.code}.
 * @param interval Monthly or annual cycle.
 * @param quantity Seats or units; 1 for a flat plan.
 * @param trialDays Days before the first charge; 0 for none.
 * @param totalCycles How many cycles the mandate covers; the provider's maximum when null.
 * @param notifyInfo Where the pre-debit notice goes; must carry at least one channel.
 * @param acceptPerChargeAfa Whether the buyer has explicitly accepted that each debit above the
 *        RBI cap needs a fresh authentication. Required when the cycle amount exceeds the cap.
 */
public record CreateSubscriptionCommand(
        Long organizationId,
        String planCode,
        BillingPeriod interval,
        int quantity,
        int trialDays,
        Integer totalCycles,
        NotifyInfo notifyInfo,
        boolean acceptPerChargeAfa,
        Instant expireBy) {

    /** Without an expiry: the provider subscription stays open until authorized or cancelled. */
    public CreateSubscriptionCommand(Long organizationId, String planCode, BillingPeriod interval, int quantity,
                                     int trialDays, Integer totalCycles, NotifyInfo notifyInfo, boolean acceptPerChargeAfa) {
        this(organizationId, planCode, interval, quantity, trialDays, totalCycles, notifyInfo, acceptPerChargeAfa, null);
    }
}

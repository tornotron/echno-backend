package org.tornotron.echno_backend.billing.gateway.dto;

import org.tornotron.echno_backend.billing.gateway.MandateMethod;

/** Asks the provider for a hosted mandate-registration flow independent of a subscription. */
public record InitiateMandateCommand(
        Long organizationId,
        String providerCustomerId,
        MandateMethod method,
        long maxAmountPaise,
        String currency,
        NotifyInfo notifyInfo) {
}

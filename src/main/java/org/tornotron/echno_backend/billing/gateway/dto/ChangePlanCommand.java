package org.tornotron.echno_backend.billing.gateway.dto;

import org.tornotron.echno_backend.billing.enums.BillingPeriod;

/**
 * Moves an existing provider subscription onto another internal plan. The provider decides the
 * mechanism (at cycle end, or cancel and recreate); the projection changes only when the
 * resulting webhook arrives.
 */
public record ChangePlanCommand(
        Long organizationId,
        String providerSubscriptionId,
        String newPlanCode,
        BillingPeriod interval,
        int quantity,
        boolean atCycleEnd,
        boolean acceptPerChargeAfa) {
}

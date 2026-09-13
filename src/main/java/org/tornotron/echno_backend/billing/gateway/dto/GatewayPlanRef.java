package org.tornotron.echno_backend.billing.gateway.dto;

import org.tornotron.echno_backend.billing.enums.BillingPeriod;

/** An internal plan code plus interval, and the provider plan id currently bound to it. */
public record GatewayPlanRef(String planCode, BillingPeriod interval, String providerPlanId) {
}

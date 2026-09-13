package org.tornotron.echno_backend.billing.gateway.dto;

/** The provider's handle for an organization. */
public record GatewayCustomer(Long organizationId, String providerCustomerId) {
}

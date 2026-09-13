package org.tornotron.echno_backend.billing.gateway.dto;

/**
 * What a provider needs to know about an organization to bill it. Built from the organization
 * row by the caller; nothing here is read from the provider.
 */
public record OrgBillingProfile(
        Long organizationId,
        String legalName,
        String gstNumber,
        String email,
        String phone,
        String currency) {
}

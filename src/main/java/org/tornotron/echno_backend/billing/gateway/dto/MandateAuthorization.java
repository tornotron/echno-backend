package org.tornotron.echno_backend.billing.gateway.dto;

import org.tornotron.echno_backend.billing.gateway.NormalizedMandateStatus;

/** The outcome of asking the provider to register a mandate: where to send the payer, and the reference. */
public record MandateAuthorization(String authUrl, String mandateReference, NormalizedMandateStatus status) {
}

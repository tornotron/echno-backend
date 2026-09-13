package org.tornotron.echno_backend.billing.webhook;

/** Published after a webhook event's inbox row commits; the async projector picks it up. */
public record BillingEventReceived(Long billingEventId) {
}

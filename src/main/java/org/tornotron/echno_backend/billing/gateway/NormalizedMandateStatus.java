package org.tornotron.echno_backend.billing.gateway;

/** The lifecycle of a recurring-payment mandate (UPI Autopay, e-NACH, card standing instruction). */
public enum NormalizedMandateStatus {
    CREATED,
    PENDING,
    AUTHORIZED,
    PAUSED,
    REVOKED,
    EXPIRED
}

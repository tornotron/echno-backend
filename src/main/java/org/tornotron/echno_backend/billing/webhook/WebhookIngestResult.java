package org.tornotron.echno_backend.billing.webhook;

/** What the inbox did with a webhook delivery. */
public enum WebhookIngestResult {
    /** Verified and stored; projection is queued. */
    ACCEPTED,
    /** Verified, but an event with this id is already in the inbox. Acknowledged and dropped. */
    DUPLICATE,
    /** The signature did not verify against the raw body. Nothing was parsed or stored. */
    REJECTED,
    /** The signature verified but the body is not an event the adapter understands. */
    UNPARSEABLE
}

package org.tornotron.echno_backend.billing.enums;

public enum SubscriptionStatus {
    INCOMPLETE,           // Payment not yet succeeded
    INCOMPLETE_EXPIRED,   // Payment failed
    TRIALING,            // In trial period
    ACTIVE,              // Active subscription
    PAST_DUE,            // Payment failed but still active
    CANCELED,            // Canceled
    UNPAID,              // Payment failed and no longer active
    PAUSED
}

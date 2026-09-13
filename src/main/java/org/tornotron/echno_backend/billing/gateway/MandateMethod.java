package org.tornotron.echno_backend.billing.gateway;

/** How a recurring mandate is registered. Drives the RBI rules in {@link MandatePolicy}. */
public enum MandateMethod {
    UPI_AUTOPAY,
    ENACH,
    CARD,
    UNKNOWN
}

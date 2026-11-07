package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/** A payment a provider already has. */
public record PayoutLookup(UUID paymentId, String providerRef, BigDecimal amount) {}

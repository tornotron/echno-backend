package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;

@Schema(description = "A checkout opened with the provider: what the browser checkout widget needs. Exactly one of "
        + "providerSubscriptionId and providerOrderId is set. No entitlement is granted here.")
@Value
@Builder
public class CheckoutSessionDto {

    @Schema(description = "Id of the checkout session row.", example = "17")
    Long id;

    @Schema(description = "The provider the checkout was opened with; NONE for a free plan activated directly.", example = "RAZORPAY")
    String provider;

    @Schema(description = "Public key id for the browser widget.", example = "rzp_test_Ab12Cd34Ef56Gh")
    String keyId;

    @Schema(description = "The provider subscription to authorize, for a recurring plan.", example = "sub_Nx8k2QhT0aYb1c")
    String providerSubscriptionId;

    @Schema(description = "The provider order to pay, for a one-off checkout.", example = "null")
    String providerOrderId;

    @Schema(description = "Plan being bought.", example = "PRO")
    String planCode;

    @Schema(description = "Billing cycle.", example = "MONTHLY")
    BillingPeriod billingPeriod;

    @Schema(description = "Cycle amount in paise.", example = "999900")
    long amountPaise;

    @Schema(description = "Currency.", example = "INR")
    String currency;

    @Schema(description = "Whether the checkout registers a recurring mandate.", example = "true")
    boolean recurring;

    @Schema(description = "Hosted authorization page, when the provider issues one as an alternative to the widget.")
    String authUrl;

    @Schema(description = "Buyer email to prefill.", example = "billing@example.com")
    String customerEmail;

    @Schema(description = "Buyer phone to prefill.", example = "+919999999999")
    String customerContact;

    @Schema(description = "Mandate terms for a recurring checkout; null for a one-off or free plan.")
    CheckoutMandateTermsDto mandate;

    @Schema(description = "When the session stops being usable.", example = "2026-09-13T12:00:00Z")
    java.time.Instant expiresAt;

    @Schema(description = "The subscription activated directly, only for a free plan (no provider round trip).")
    SubscriptionDto subscription;
}

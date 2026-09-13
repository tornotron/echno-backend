package org.tornotron.echno_backend.billing.gateway;

import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.dto.ChangePlanCommand;
import org.tornotron.echno_backend.billing.gateway.dto.CreateSubscriptionCommand;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayCustomer;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayPlanRef;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.InitiateMandateCommand;
import org.tornotron.echno_backend.billing.gateway.dto.MandateAuthorization;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.gateway.dto.OrgBillingProfile;

import java.util.List;

/**
 * The provider-neutral port to a payment provider. Only internal DTOs cross it; no provider
 * type or vocabulary does. One adapter per provider implements it, and exactly one instance is
 * wired per environment by {@link BillingGatewayConfiguration}: the Razorpay adapter when
 * {@code echno.billing.provider=razorpay} and its keys are present, otherwise the no-op
 * gateway, under which the application runs on manually provisioned subscriptions as it did
 * before any gateway existed.
 *
 * <p>Nothing on the request path calls this port to decide entitlement. The paywall reads the
 * {@code Subscription} projection, and the projection is written from the events this port
 * parses out of webhooks. See section 5 of the payment integration design.
 */
public interface BillingGateway {

    ProviderId providerId();

    /** Whether a real provider is behind this gateway. False for the no-op gateway. */
    boolean isEnabled();

    /** Finds or creates the provider customer that stands for the organization. */
    GatewayCustomer ensureCustomer(OrgBillingProfile org);

    /** Finds or creates the provider plan bound to an internal plan and interval. */
    GatewayPlanRef ensurePlan(Plan plan, BillingPeriod interval);

    /** Creates a provider subscription; the result carries the hosted authorization URL. */
    GatewaySubscription createSubscription(CreateSubscriptionCommand cmd);

    GatewaySubscription fetchSubscription(String providerSubscriptionId);

    void pauseSubscription(String providerSubscriptionId);

    void resumeSubscription(String providerSubscriptionId);

    void cancelSubscription(String providerSubscriptionId, boolean atCycleEnd);

    GatewaySubscription changePlan(ChangePlanCommand cmd);

    /** Starts a hosted mandate registration; returns where to send the payer. */
    MandateAuthorization initiateMandate(InitiateMandateCommand cmd);

    /** Whether the signature header authenticates the raw body. The only gate on the webhook path. */
    boolean verifySignature(byte[] rawBody, String signatureHeader);

    /** The normalized events in a verified webhook body: zero or more. */
    List<NormalizedBillingEvent> parseEvents(byte[] rawBody);
}

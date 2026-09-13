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
 * The gateway wired when no provider is configured. Every provider call refuses with a clear
 * message, every signature fails to verify (so a webhook posted to an environment without a
 * provider is rejected rather than acted on), and no event is ever parsed. Under it the
 * application runs on manually provisioned subscriptions only, exactly as before.
 */
public class NoOpBillingGateway implements BillingGateway {

    private static final String MESSAGE =
            "No billing provider is configured (echno.billing.provider=none); subscriptions are provisioned manually";

    @Override
    public ProviderId providerId() {
        return ProviderId.MANUAL;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public GatewayCustomer ensureCustomer(OrgBillingProfile org) {
        throw new BillingGatewayException(MESSAGE);
    }

    @Override
    public GatewayPlanRef ensurePlan(Plan plan, BillingPeriod interval) {
        throw new BillingGatewayException(MESSAGE);
    }

    @Override
    public GatewaySubscription createSubscription(CreateSubscriptionCommand cmd) {
        throw new BillingGatewayException(MESSAGE);
    }

    @Override
    public GatewaySubscription fetchSubscription(String providerSubscriptionId) {
        throw new BillingGatewayException(MESSAGE);
    }

    @Override
    public void pauseSubscription(String providerSubscriptionId) {
        throw new BillingGatewayException(MESSAGE);
    }

    @Override
    public void resumeSubscription(String providerSubscriptionId) {
        throw new BillingGatewayException(MESSAGE);
    }

    @Override
    public GatewaySubscription cancelSubscription(String providerSubscriptionId, boolean atCycleEnd) {
        throw new BillingGatewayException(MESSAGE);
    }

    @Override
    public GatewaySubscription changePlan(ChangePlanCommand cmd) {
        throw new BillingGatewayException(MESSAGE);
    }

    @Override
    public MandateAuthorization initiateMandate(InitiateMandateCommand cmd) {
        throw new BillingGatewayException(MESSAGE);
    }

    @Override
    public boolean verifySignature(byte[] rawBody, String signatureHeader) {
        return false;
    }

    @Override
    public List<NormalizedBillingEvent> parseEvents(byte[] rawBody) {
        return List.of();
    }
}

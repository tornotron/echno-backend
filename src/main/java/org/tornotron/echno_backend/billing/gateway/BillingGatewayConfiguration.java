package org.tornotron.echno_backend.billing.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayBillingGateway;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayCheckoutSignature;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayEventParser;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayRestClient;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayWebhookSignature;
import org.tornotron.echno_backend.billing.repositories.BillingCustomerRepository;
import org.tornotron.echno_backend.billing.repositories.GatewayPlanMappingRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;

/**
 * Wires exactly one {@link BillingGateway}. Razorpay is wired only when it is selected and
 * both API keys are present; a selected provider with missing keys logs a warning and falls
 * back to the no-op gateway, so a misconfigured environment degrades to "no payments" rather
 * than failing to boot or, worse, half-working.
 */
@Slf4j
@Configuration
public class BillingGatewayConfiguration {

    @Bean
    public BillingGateway billingGateway(BillingGatewayProperties properties,
                                         MandatePolicy mandatePolicy,
                                         BillingCustomerRepository customers,
                                         GatewayPlanMappingRepository planMappings,
                                         PlanRepository plans) {
        if (!properties.isRazorpay()) {
            log.info("Billing gateway: none configured; subscriptions are provisioned manually");
            return new NoOpBillingGateway();
        }
        BillingGatewayProperties.Razorpay razorpay = properties.getRazorpay();
        if (!razorpay.hasApiKeys()) {
            log.warn("Billing gateway: echno.billing.provider=razorpay but RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET are empty; "
                    + "falling back to the no-op gateway");
            return new NoOpBillingGateway();
        }
        if (!razorpay.hasWebhookSecret()) {
            log.warn("Billing gateway: RAZORPAY_WEBHOOK_SECRET is empty; every webhook will be rejected until it is set");
        }
        log.info("Billing gateway: Razorpay at {}", razorpay.getBaseUrl());
        return new RazorpayBillingGateway(
                new RazorpayRestClient(razorpay),
                new RazorpayWebhookSignature(razorpay.getWebhookSecret()),
                new RazorpayCheckoutSignature(razorpay.getKeySecret()),
                razorpay.getKeyId(),
                new RazorpayEventParser(),
                mandatePolicy,
                properties.getCurrency(),
                customers,
                planMappings,
                plans);
    }
}

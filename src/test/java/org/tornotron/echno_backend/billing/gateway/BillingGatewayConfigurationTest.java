package org.tornotron.echno_backend.billing.gateway;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayBillingGateway;
import org.tornotron.echno_backend.billing.repositories.BillingCustomerRepository;
import org.tornotron.echno_backend.billing.repositories.GatewayPlanMappingRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BillingGatewayConfigurationTest {

    private final BillingGatewayConfiguration configuration = new BillingGatewayConfiguration();

    @Test
    void providerNoneWiresTheNoOpGateway() {
        BillingGateway gateway = build(props("none", "", "", ""));

        assertThat(gateway).isInstanceOf(NoOpBillingGateway.class);
        assertThat(gateway.isEnabled()).isFalse();
        assertThat(gateway.providerId()).isEqualTo(ProviderId.MANUAL);
        assertThat(gateway.verifySignature("{}".getBytes(), "anything")).isFalse();
        assertThat(gateway.parseEvents("{}".getBytes())).isEmpty();
        assertThatThrownBy(() -> gateway.fetchSubscription("sub_x"))
                .isInstanceOf(BillingGatewayException.class)
                .hasMessageContaining("provider=none");
    }

    @Test
    void razorpayWithoutKeysFallsBackToTheNoOpGateway() {
        assertThat(build(props("razorpay", "", "", "whsec"))).isInstanceOf(NoOpBillingGateway.class);
        assertThat(build(props("razorpay", "rzp_test_x", "", "whsec"))).isInstanceOf(NoOpBillingGateway.class);
    }

    @Test
    void razorpayWithKeysWiresTheAdapter() {
        BillingGateway gateway = build(props("razorpay", "rzp_test_x", "secret", "whsec"));

        assertThat(gateway).isInstanceOf(RazorpayBillingGateway.class);
        assertThat(gateway.isEnabled()).isTrue();
        assertThat(gateway.providerId()).isEqualTo(ProviderId.RAZORPAY);
    }

    @Test
    void theProviderNameIsCaseAndWhitespaceInsensitive() {
        assertThat(build(props(" Razorpay ", "k", "s", "w"))).isInstanceOf(RazorpayBillingGateway.class);
        assertThat(build(props(null, "k", "s", "w"))).isInstanceOf(NoOpBillingGateway.class);
    }

    private BillingGateway build(BillingGatewayProperties properties) {
        return configuration.billingGateway(properties, new MandatePolicy(properties),
                mock(BillingCustomerRepository.class), mock(GatewayPlanMappingRepository.class), mock(PlanRepository.class));
    }

    private static BillingGatewayProperties props(String provider, String keyId, String secret, String webhook) {
        BillingGatewayProperties properties = new BillingGatewayProperties();
        properties.setProvider(provider);
        properties.getRazorpay().setKeyId(keyId);
        properties.getRazorpay().setKeySecret(secret);
        properties.getRazorpay().setWebhookSecret(webhook);
        return properties;
    }
}

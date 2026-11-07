package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.BillPaymentGateways;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.ContractPaymentProperties;

/** What carries bill payments under each configuration, and that going live is opt-in per organization. */
class BillPaymentGatewaysTest {

    private static final long LISTED = 7L;
    private static final long OTHER = 8L;

    private static ContractPaymentProperties properties(String provider) {
        ContractPaymentProperties properties = new ContractPaymentProperties();
        properties.setProvider(provider);
        return properties;
    }

    private static ContractPaymentProperties razorpay() {
        ContractPaymentProperties properties = properties("razorpay");
        properties.getRazorpay().setKeyId("rzp_test_x");
        properties.getRazorpay().setKeySecret("secret");
        properties.getRazorpay().setWebhookSecret("hook");
        properties.setAccountNumber("2323230000000000");
        properties.getOrganizationIds().add(LISTED);
        return properties;
    }

    @Test
    void theDefaultIsManual() {
        BillPaymentGateways gateways = new BillPaymentGateways(new ContractPaymentProperties());
        assertThat(gateways.providerFor(LISTED)).isEqualTo(BillPaymentProvider.MANUAL);
        assertThat(gateways.forOrganization(LISTED)).isEmpty();
    }

    @Test
    void fakeServesEveryOrganization() {
        BillPaymentGateways gateways = new BillPaymentGateways(properties("fake"));
        assertThat(gateways.providerFor(LISTED)).isEqualTo(BillPaymentProvider.FAKE);
        assertThat(gateways.providerFor(OTHER)).isEqualTo(BillPaymentProvider.FAKE);
    }

    @Test
    void razorpayServesOnlyTheListedOrganizationsSoNoOneDrawsOnAnothersAccount() {
        BillPaymentGateways gateways = new BillPaymentGateways(razorpay());
        assertThat(gateways.providerFor(LISTED)).isEqualTo(BillPaymentProvider.RAZORPAY);
        assertThat(gateways.providerFor(OTHER)).isEqualTo(BillPaymentProvider.MANUAL);
    }

    @Test
    void anIncompleteRazorpayConfigurationFallsBackToManual() {
        ContractPaymentProperties noKeys = razorpay();
        noKeys.getRazorpay().setKeySecret("");
        ContractPaymentProperties noAccount = razorpay();
        noAccount.setAccountNumber(" ");
        ContractPaymentProperties noWebhook = razorpay();
        noWebhook.getRazorpay().setWebhookSecret("");
        for (ContractPaymentProperties incomplete : new ContractPaymentProperties[] {noKeys, noAccount, noWebhook}) {
            assertThat(new BillPaymentGateways(incomplete).providerFor(LISTED)).isEqualTo(BillPaymentProvider.MANUAL);
        }
    }
}

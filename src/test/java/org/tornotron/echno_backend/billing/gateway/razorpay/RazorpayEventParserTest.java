package org.tornotron.echno_backend.billing.gateway.razorpay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;
import org.tornotron.echno_backend.billing.gateway.NormalizedEventType;
import org.tornotron.echno_backend.billing.gateway.NormalizedMandateStatus;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RazorpayEventParserTest {

    private final RazorpayEventParser mapper = new RazorpayEventParser();

    @ParameterizedTest
    @CsvSource({
            "subscription.authenticated, SUBSCRIPTION_AUTHENTICATED, AUTHENTICATED",
            "subscription.activated, SUBSCRIPTION_ACTIVATED, ACTIVE",
            "subscription.charged, SUBSCRIPTION_CHARGED, ACTIVE",
            "subscription.pending, SUBSCRIPTION_PENDING, PAYMENT_FAILED_RETRYING",
            "subscription.halted, SUBSCRIPTION_HALTED, HALTED",
            "subscription.cancelled, SUBSCRIPTION_CANCELLED, CANCELLED"
    })
    void subscriptionEventsMapToTheNormalizedTypeAndStatus(String fixture, NormalizedEventType type,
                                                            NormalizedSubscriptionStatus status) {
        NormalizedBillingEvent event = only(mapper.parse(RazorpayFixtures.body(fixture)));

        assertThat(event.provider()).isEqualTo(ProviderId.RAZORPAY);
        assertThat(event.type()).isEqualTo(type);
        assertThat(event.organizationId()).isEqualTo(4242L);
        assertThat(event.planCode()).isEqualTo("fixture-pro");
        assertThat(event.providerSubscriptionId()).isEqualTo("sub_FixtureSub00001");
        assertThat(event.providerCustomerId()).isEqualTo("cust_FixtureCust001");
        assertThat(event.providerPlanId()).isEqualTo("plan_FixturePlan001");
        assertThat(event.subscription()).isNotNull();
        assertThat(event.subscription().status()).isEqualTo(status);
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void periodBoundsComeFromCurrentStartAndEndInEpochSeconds() {
        NormalizedBillingEvent event = only(mapper.parse(RazorpayFixtures.body("subscription.charged")));

        assertThat(event.subscription().currentPeriodStart()).isEqualTo(Instant.ofEpochSecond(1727740800L));
        assertThat(event.subscription().currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1730419200L));
        assertThat(event.subscription().nextChargeAt()).isEqualTo(Instant.ofEpochSecond(1730419200L));
        assertThat(event.occurredAt()).isEqualTo(Instant.ofEpochSecond(1727740801L));
    }

    @Test
    void anAuthenticatedSubscriptionHasNoPeriodYet() {
        NormalizedBillingEvent event = only(mapper.parse(RazorpayFixtures.body("subscription.authenticated")));

        assertThat(event.subscription().currentPeriodStart()).isNull();
        assertThat(event.subscription().currentPeriodEnd()).isNull();
        assertThat(event.subscription().authUrl()).isEqualTo("https://rzp.io/i/fixture");
    }

    @Test
    void aConfirmedTokenIsAnAuthorizedMandate() {
        NormalizedBillingEvent event = only(mapper.parse(RazorpayFixtures.body("token.confirmed")));

        assertThat(event.type()).isEqualTo(NormalizedEventType.MANDATE_AUTHORIZED);
        assertThat(event.mandateReference()).isEqualTo("token_FixtureTok0001");
        assertThat(event.mandateMethod()).isEqualTo(MandateMethod.UPI_AUTOPAY);
        assertThat(event.mandateStatus()).isEqualTo(NormalizedMandateStatus.AUTHORIZED);
        assertThat(event.mandateMaxAmountPaise()).isEqualTo(1_500_000L);
        assertThat(event.organizationId()).as("a token's notes are never read; the customer mapping resolves it").isNull();
        assertThat(event.providerCustomerId()).isEqualTo("cust_FixtureCust001");
        assertThat(event.subscription()).isNull();
    }

    @Test
    void aFailedPaymentDoesNotCarryTheOrganizationFromItsOwnNotes() {
        NormalizedBillingEvent event = only(mapper.parse(RazorpayFixtures.body("payment.failed")));

        assertThat(event.type()).isEqualTo(NormalizedEventType.PAYMENT_FAILED);
        assertThat(event.organizationId()).as("payment notes are what the browser passed to the widget").isNull();
        assertThat(event.providerCustomerId()).isEqualTo("cust_FixtureCust001");
        assertThat(event.subscription()).isNull();
    }

    @Test
    void aPaymentBoundToASubscriptionMayCarryTheOrganizationFromItsNotes() {
        byte[] body = new String(RazorpayFixtures.body("payment.failed"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"status\":\"failed\"", "\"status\":\"failed\",\"subscription_id\":\"sub_FixtureSub00001\"")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        NormalizedBillingEvent event = only(mapper.parse(body));

        assertThat(event.providerSubscriptionId()).isEqualTo("sub_FixtureSub00001");
        assertThat(event.organizationId()).isEqualTo(4242L);
    }

    @Test
    void anUnknownEventNameIsIgnoredRatherThanRefused() {
        byte[] body = "{\"entity\":\"event\",\"event\":\"refund.created\",\"payload\":{},\"created_at\":1}"
                .getBytes(StandardCharsets.UTF_8);
        NormalizedBillingEvent event = only(mapper.parse(body));

        assertThat(event.type()).isEqualTo(NormalizedEventType.IGNORED);
        assertThat(event.organizationId()).isNull();
    }

    @Test
    void theEventIdIsADigestOfTheBodySoARedeliveryHasTheSameId() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        assertThat(only(mapper.parse(body)).providerEventId())
                .isEqualTo(only(mapper.parse(body.clone())).providerEventId())
                .hasSize(64);
        assertThat(only(mapper.parse(RazorpayFixtures.body("subscription.charged"))).providerEventId())
                .isNotEqualTo(only(mapper.parse(body)).providerEventId());
    }

    @Test
    void aBodyThatIsNotAnEventEnvelopeIsRefused() {
        assertThatThrownBy(() -> mapper.parse("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BillingGatewayException.class);
        assertThatThrownBy(() -> mapper.parse("{\"entity\":\"payment\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BillingGatewayException.class);
    }

    @Test
    void anUnknownSubscriptionStatusIsRefusedRatherThanGuessed() {
        assertThatThrownBy(() -> RazorpayEventParser.toStatus("something-new"))
                .isInstanceOf(BillingGatewayException.class);
    }

    private static NormalizedBillingEvent only(List<NormalizedBillingEvent> events) {
        assertThat(events).hasSize(1);
        return events.getFirst();
    }
}

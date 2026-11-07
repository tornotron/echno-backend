package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayWebhookSignature;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.ContractPaymentProperties;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutResult;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.webhook.BillPayoutReceived;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.webhook.BillPayoutWebhookService;

/** The webhook's only gate is the signature over the raw bytes; what passes it is read and handed on. */
class BillPayoutWebhookServiceTest {

    private static final String SECRET = "payout-webhook-secret";
    private static final UUID PAYMENT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final List<Object> published = new ArrayList<>();

    private BillPayoutWebhookService service(String secret) {
        ContractPaymentProperties properties = new ContractPaymentProperties();
        properties.getRazorpay().setWebhookSecret(secret);
        return new BillPayoutWebhookService(properties, published::add);
    }

    private static String event(String status, String reference) {
        return "{\"entity\":\"event\",\"event\":\"payout." + status + "\",\"payload\":{\"payout\":{\"entity\":"
                + "{\"id\":\"pout_1\",\"status\":\"" + status + "\",\"amount\":40000,\"utr\":\"UTR9\",\"reference_id\":"
                + (reference == null ? "null" : "\"" + reference + "\"") + "}}}}";
    }

    private static String sign(String body, String secret) {
        return new RazorpayWebhookSignature(secret).sign(body.getBytes(StandardCharsets.UTF_8));
    }

    private boolean deliver(BillPayoutWebhookService service, String body, String signature) {
        return service.ingest(body.getBytes(StandardCharsets.UTF_8), signature);
    }

    @Test
    void aSignedEventIsHandedOnWithItsPaymentAndOutcome() {
        String body = event("processed", PAYMENT.toString());
        assertThat(deliver(service(SECRET), body, sign(body, SECRET))).isTrue();
        assertThat(published).singleElement().isInstanceOfSatisfying(BillPayoutReceived.class, received -> {
            assertThat(received.paymentId()).isEqualTo(PAYMENT);
            assertThat(received.result().outcome()).isEqualTo(PayoutResult.Outcome.SETTLED);
            assertThat(received.result().bankReference()).isEqualTo("UTR9");
            assertThat(received.result().amountPaise()).isEqualTo(40000L);
        });
    }

    @Test
    void aBadOrMissingSignatureIsRefusedAndNothingIsHandedOn() {
        String body = event("processed", PAYMENT.toString());
        assertThat(deliver(service(SECRET), body, sign(body, "another-secret"))).isFalse();
        assertThat(deliver(service(SECRET), body, null)).isFalse();
        assertThat(deliver(service(SECRET), body + " ", sign(body, SECRET))).as("a changed body").isFalse();
        assertThat(published).isEmpty();
    }

    @Test
    void withNoSecretConfiguredEveryDeliveryIsRefused() {
        String body = event("processed", PAYMENT.toString());
        assertThat(deliver(service(""), body, sign(body, SECRET))).isFalse();
        assertThat(deliver(service(""), body, "anything")).isFalse();
        assertThat(published).isEmpty();
    }

    @Test
    void aPayoutEchnoDidNotRaiseIsAcknowledgedAndDropped() {
        for (String reference : new String[] {null, "someone-elses-payout"}) {
            String body = event("processed", reference);
            assertThat(deliver(service(SECRET), body, sign(body, SECRET))).isTrue();
        }
        String notAPayout = "{\"event\":\"account.updated\",\"payload\":{}}";
        assertThat(deliver(service(SECRET), notAPayout, sign(notAPayout, SECRET))).isTrue();
        String garbage = "not json";
        assertThat(deliver(service(SECRET), garbage, sign(garbage, SECRET))).isTrue();
        assertThat(published).isEmpty();
    }
}

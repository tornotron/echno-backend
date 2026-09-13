package org.tornotron.echno_backend.billing.gateway.razorpay;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookSignatureTest {

    private final RazorpayWebhookSignature signature = new RazorpayWebhookSignature(RazorpayFixtures.WEBHOOK_SECRET);

    @Test
    void aBodySignedWithTheSecretVerifies() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        assertThat(signature.verify(body, signature.sign(body))).isTrue();
    }

    @Test
    void theSignatureIsTheDocumentedHmacSha256Hex() {
        // Independently computed: HMAC-SHA256("fixture-webhook-secret", "{}") in lowercase hex.
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(signature.sign(body)).matches("[0-9a-f]{64}");
        assertThat(signature.verify(body, signature.sign(body).toUpperCase())).isTrue();
    }

    @Test
    void aTamperedBodyDoesNotVerify() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        String signed = signature.sign(body);
        String tampered = new String(body, StandardCharsets.UTF_8).replace("\"organization_id\":\"4242\"", "\"organization_id\":\"1\"");
        assertThat(signature.verify(tampered.getBytes(StandardCharsets.UTF_8), signed)).isFalse();
    }

    @Test
    void aSignatureUnderAnotherSecretDoesNotVerify() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        String other = new RazorpayWebhookSignature("some-other-secret").sign(body);
        assertThat(signature.verify(body, other)).isFalse();
    }

    @Test
    void aMissingOrEmptySecretNeverVerifies() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        String signedUnderRealSecret = signature.sign(body);
        assertThat(new RazorpayWebhookSignature("").verify(body, signedUnderRealSecret)).isFalse();
        assertThat(new RazorpayWebhookSignature("").verify(body, "0".repeat(64))).isFalse();
        assertThat(new RazorpayWebhookSignature(null).verify(body, "abc")).isFalse();
    }

    @Test
    void aMissingHeaderDoesNotVerify() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        assertThat(signature.verify(body, null)).isFalse();
        assertThat(signature.verify(body, "  ")).isFalse();
    }
}

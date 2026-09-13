package org.tornotron.echno_backend.billing.gateway.razorpay;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Recorded Razorpay webhook bodies, shaped from the public webhook reference, under src/test/resources/fixtures/razorpay. */
public final class RazorpayFixtures {

    public static final String WEBHOOK_SECRET = "fixture-webhook-secret";

    private RazorpayFixtures() {
    }

    public static byte[] body(String eventName) {
        String path = "/fixtures/razorpay/" + eventName + ".json";
        try (InputStream in = RazorpayFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture at " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String signature(byte[] body) {
        return new RazorpayWebhookSignature(WEBHOOK_SECRET).sign(body);
    }
}

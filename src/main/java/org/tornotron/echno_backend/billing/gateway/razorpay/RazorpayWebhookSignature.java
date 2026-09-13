package org.tornotron.echno_backend.billing.gateway.razorpay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Razorpay signs a webhook as the lowercase hex HMAC-SHA256 of the raw request body under the
 * webhook secret, sent in {@code X-Razorpay-Signature}. Verified over the untouched bytes,
 * before any parsing, with a constant-time comparison. An empty secret verifies nothing.
 */
public class RazorpayWebhookSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public RazorpayWebhookSignature(String webhookSecret) {
        this.secret = webhookSecret == null ? new byte[0] : webhookSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(byte[] rawBody, String signatureHeader) {
        if (secret.length == 0 || rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        byte[] expected = sign(rawBody).getBytes(StandardCharsets.UTF_8);
        byte[] presented = signatureHeader.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }

    /** The signature Razorpay would send for this body; also what the tests use to sign fixtures. */
    public String sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable in this JVM", e);
        }
    }
}

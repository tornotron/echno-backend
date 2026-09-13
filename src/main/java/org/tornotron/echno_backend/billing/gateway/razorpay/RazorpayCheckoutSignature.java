package org.tornotron.echno_backend.billing.gateway.razorpay;

import org.tornotron.echno_backend.billing.gateway.dto.CheckoutSignature;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Razorpay signs the Checkout.js success payload as the lowercase hex HMAC-SHA256, under the
 * API key secret, of {@code payment_id|subscription_id} for a subscription and
 * {@code order_id|payment_id} for an order. Verified with a constant-time comparison; an empty
 * secret verifies nothing.
 */
public class RazorpayCheckoutSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public RazorpayCheckoutSignature(String keySecret) {
        this.secret = keySecret == null ? new byte[0] : keySecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(CheckoutSignature result) {
        if (secret.length == 0 || result == null || isBlank(result.paymentId()) || isBlank(result.signature())) {
            return false;
        }
        String message;
        if (result.isForSubscription()) {
            message = result.paymentId() + "|" + result.subscriptionId();
        } else if (!isBlank(result.orderId())) {
            message = result.orderId() + "|" + result.paymentId();
        } else {
            return false;
        }
        byte[] expected = sign(message).getBytes(StandardCharsets.UTF_8);
        byte[] presented = result.signature().trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }

    /** The signature Razorpay would return for this message; what the tests use to sign fixtures. */
    public String sign(String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable in this JVM", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package org.tornotron.echno_backend.billing.gateway.razorpay;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.gateway.dto.CheckoutSignature;

import static org.assertj.core.api.Assertions.assertThat;

/** The Checkout.js result is signed under the key secret over {@code payment|subscription}, or {@code order|payment}. */
class RazorpayCheckoutSignatureTest {

    private final RazorpayCheckoutSignature signature = new RazorpayCheckoutSignature("key_secret");

    @Test
    void aSubscriptionResultVerifiesOnlyWithTheRightPairAndSecret() {
        String good = signature.sign("pay_1|sub_1");
        assertThat(signature.verify(new CheckoutSignature("pay_1", "sub_1", null, good))).isTrue();
        assertThat(signature.verify(new CheckoutSignature("pay_1", "sub_1", null, good.toUpperCase()))).isTrue();
        assertThat(signature.verify(new CheckoutSignature("pay_2", "sub_1", null, good))).isFalse();
        assertThat(signature.verify(new CheckoutSignature("pay_1", "sub_2", null, good))).isFalse();
        assertThat(new RazorpayCheckoutSignature("other").verify(new CheckoutSignature("pay_1", "sub_1", null, good))).isFalse();
        assertThat(new RazorpayCheckoutSignature("").verify(new CheckoutSignature("pay_1", "sub_1", null, good))).isFalse();
    }

    @Test
    void anOrderResultIsSignedOrderFirst() {
        String good = signature.sign("order_1|pay_1");
        assertThat(signature.verify(new CheckoutSignature("pay_1", null, "order_1", good))).isTrue();
        assertThat(signature.verify(new CheckoutSignature("pay_1", null, null, good))).isFalse();
        assertThat(signature.verify(new CheckoutSignature("pay_1", null, "order_1", ""))).isFalse();
    }
}

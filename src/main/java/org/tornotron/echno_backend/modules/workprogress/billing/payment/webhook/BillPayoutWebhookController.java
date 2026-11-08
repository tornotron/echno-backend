package org.tornotron.echno_backend.modules.workprogress.billing.payment.webhook;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay's deliveries about bill payments, on their own path and with their own secret. Public at
 * the security layer because the provider calls it with no session: the HMAC signature over the raw
 * body is the only gate, and it is checked before a byte is parsed. The handler touches no tenant data;
 * what it verified goes to an async listener on another thread.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/billing/webhooks")
@RequiredArgsConstructor
public class BillPayoutWebhookController {

    private final BillPayoutWebhookService service;

    /** 200 for what was understood or is not ours, so Razorpay stops retrying; 401 for a bad signature. */
    @PreAuthorize("permitAll()")
    @PostMapping(value = "/razorpay-payouts", consumes = "*/*")
    public ResponseEntity<Void> razorpayPayouts(@RequestBody byte[] rawBody,
                                                @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        return service.ingest(rawBody, signature)
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}

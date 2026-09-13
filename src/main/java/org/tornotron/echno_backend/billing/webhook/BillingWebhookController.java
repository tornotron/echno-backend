package org.tornotron.echno_backend.billing.webhook;

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
 * The provider-facing webhook endpoint. Public at the security layer because the provider
 * calls it with no session; the HMAC signature over the raw body is the only gate, and it
 * is checked before a single byte is parsed. The body is bound as {@code byte[]} so no
 * converter can re-serialize it between the wire and the verifier.
 *
 * <p>{@code permitAll()} here is safe under the public-endpoint exposure rule because the
 * handler reaches only the inbox, which is global by design, and hands the rest to an async
 * listener on another thread. Hidden from the OpenAPI document: it is not part of the API a
 * client consumes.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/billing/webhooks")
@RequiredArgsConstructor
public class BillingWebhookController {

    private final BillingWebhookService service;

    /**
     * Razorpay delivery. 200 for accepted and for duplicates (so the provider stops retrying),
     * 401 for a signature that does not verify, 400 for a verified body that is not an event.
     */
    @PreAuthorize("permitAll()")
    @PostMapping(value = "/razorpay", consumes = "*/*")
    public ResponseEntity<Void> razorpay(@RequestBody byte[] rawBody,
                                         @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                         @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        return switch (service.ingest(rawBody, signature, eventId)) {
            case ACCEPTED, DUPLICATE -> ResponseEntity.ok().build();
            case REJECTED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            case UNPARSEABLE -> ResponseEntity.badRequest().build();
        };
    }
}

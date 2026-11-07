package org.tornotron.echno_backend.modules.workprogress.billing.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayWebhookSignature;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.BillPaymentGatewayException;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.ContractPaymentProperties;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutResult;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.RazorpayPayoutGateway;

/**
 * Verifies a delivery over the raw bytes, reads the payout out of it and hands it on. A delivery about a
 * payout Echno did not raise, or a kind of event it does not know, is acknowledged and dropped. Nothing
 * is kept here: the payment row holds the state and only moves forward, so a replay is harmless, and an
 * answer lost after this point is recovered by Sync on the payment.
 */
@Slf4j
@Service
public class BillPayoutWebhookService {

    private final RazorpayWebhookSignature signature;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BillPayoutWebhookService(ContractPaymentProperties properties, ApplicationEventPublisher publisher) {
        this.signature = new RazorpayWebhookSignature(properties.getRazorpay().getWebhookSecret());
        this.publisher = publisher;
    }

    /** @return false when the signature does not verify, including when no secret is configured. */
    public boolean ingest(byte[] rawBody, String signatureHeader) {
        if (!signature.verify(rawBody, signatureHeader)) {
            log.warn("Bill payout webhook rejected: signature did not verify ({} bytes)", rawBody == null ? 0 : rawBody.length);
            return false;
        }
        JsonNode payout;
        try {
            payout = objectMapper.readTree(rawBody).path("payload").path("payout").path("entity");
        } catch (IOException e) {
            log.warn("Bill payout webhook verified but not readable: {}", e.getMessage());
            return true;
        }
        UUID paymentId = paymentId(payout);
        if (paymentId == null) {
            log.info("Bill payout webhook for a payout with no Echno reference; dropped");
            return true;
        }
        PayoutResult result;
        try {
            result = RazorpayPayoutGateway.toResult(payout);
        } catch (BillPaymentGatewayException e) {
            log.warn("Bill payout webhook for payment {} had no id or status; dropped", paymentId);
            return true;
        }
        publisher.publishEvent(new BillPayoutReceived(paymentId, result));
        return true;
    }

    private static UUID paymentId(JsonNode payout) {
        JsonNode reference = payout.get("reference_id");
        if (reference == null || reference.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(reference.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

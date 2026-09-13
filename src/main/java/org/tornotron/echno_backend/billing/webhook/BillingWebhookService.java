package org.tornotron.echno_backend.billing.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * The synchronous half of webhook ingestion: verify the signature over the raw bytes, parse
 * the same bytes, write the inbox row, publish for the projector, and return. Nothing here
 * touches the entitlement; that happens on the projector's thread after this transaction
 * commits, so a slow projection never makes the provider time out and redeliver.
 *
 * <p>The inbox row is keyed on the provider's event id from the {@code X-Razorpay-Event-Id}
 * header when present, else on the body digest the parser derives. Either way a redelivery
 * is a duplicate key, seen either by the lookup before the insert or, in a race between two
 * deliveries of the same event, by the unique constraint on the insert.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingWebhookService {

    private final BillingGateway gateway;
    private final BillingEventRepository events;
    private final ApplicationEventPublisher publisher;
    private final PlatformTransactionManager transactionManager;

    /**
     * Ingests one delivery.
     *
     * @param rawBody The request body exactly as received.
     * @param signatureHeader The provider's signature header.
     * @param providerEventIdHeader The provider's event id header, or null when not sent.
     * @return What was done with the delivery.
     */
    @Transactional
    public WebhookIngestResult ingest(byte[] rawBody, String signatureHeader, String providerEventIdHeader) {
        if (!gateway.verifySignature(rawBody, signatureHeader)) {
            log.warn("Billing webhook rejected: signature did not verify ({} bytes)", rawBody == null ? 0 : rawBody.length);
            return WebhookIngestResult.REJECTED;
        }
        List<NormalizedBillingEvent> parsed;
        try {
            parsed = gateway.parseEvents(rawBody);
        } catch (BillingGatewayException e) {
            log.warn("Billing webhook verified but not understood: {}", e.getMessage());
            return WebhookIngestResult.UNPARSEABLE;
        }
        if (parsed.isEmpty()) {
            return WebhookIngestResult.UNPARSEABLE;
        }
        NormalizedBillingEvent first = parsed.getFirst();
        String eventId = providerEventIdHeader != null && !providerEventIdHeader.isBlank()
                ? providerEventIdHeader.trim()
                : first.providerEventId();
        if (events.findByProviderAndProviderEventId(first.provider(), eventId).isPresent()) {
            log.info("Billing webhook duplicate: {} {} already in the inbox", first.provider(), eventId);
            return WebhookIngestResult.DUPLICATE;
        }
        BillingEvent row = BillingEvent.builder()
                .provider(first.provider())
                .providerEventId(eventId)
                .eventType(first.type().name())
                .organizationId(first.organizationId())
                .providerSubscriptionId(first.providerSubscriptionId())
                .payload(new String(rawBody, StandardCharsets.UTF_8))
                .signatureVerified(true)
                .receivedAt(Instant.now())
                .occurredAt(first.occurredAt())
                .build();
        try {
            // Its own transaction: a unique-key refusal then rolls back the insert alone. Caught
            // inside the joined transaction it left that transaction rollback-only, so the
            // duplicate answer became an UnexpectedRollbackException and a 500 the provider retried.
            TransactionTemplate insert = new TransactionTemplate(transactionManager);
            insert.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            BillingEvent toInsert = row;
            row = insert.execute(status -> events.saveAndFlush(toInsert));
        } catch (DataIntegrityViolationException e) {
            log.info("Billing webhook duplicate under race: {} {}", first.provider(), eventId);
            return WebhookIngestResult.DUPLICATE;
        }
        publisher.publishEvent(new BillingEventReceived(row.getId()));
        log.info("Billing webhook accepted: {} {} ({}) as inbox row {}", first.provider(), eventId, first.type(), row.getId());
        return WebhookIngestResult.ACCEPTED;
    }
}

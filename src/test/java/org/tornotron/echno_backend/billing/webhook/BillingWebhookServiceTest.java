package org.tornotron.echno_backend.billing.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.MandatePolicy;
import org.tornotron.echno_backend.billing.gateway.NoOpBillingGateway;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayBillingGateway;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayEventParser;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayFixtures;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayWebhookSignature;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BillingWebhookServiceTest {

    private final BillingEventRepository events = mock(BillingEventRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private BillingWebhookService service;

    @BeforeEach
    void wire() {
        BillingGateway gateway = new RazorpayBillingGateway(null, new RazorpayWebhookSignature(RazorpayFixtures.WEBHOOK_SECRET),
                new RazorpayEventParser(), new MandatePolicy(MandatePolicy.DEFAULT_AFA_CAP_PAISE), "INR", null, null, null);
        service = new BillingWebhookService(gateway, events, publisher);
        when(events.findByProviderAndProviderEventId(any(), anyString())).thenReturn(Optional.empty());
        when(events.saveAndFlush(any(BillingEvent.class))).thenAnswer(inv -> {
            BillingEvent row = inv.getArgument(0);
            row.setId(77L);
            return row;
        });
    }

    @Test
    void aTamperedBodyIsRejectedBeforeAnythingIsParsedOrStored() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        String signed = RazorpayFixtures.signature(body);
        byte[] tampered = new String(body, StandardCharsets.UTF_8)
                .replace("\"organization_id\":\"4242\"", "\"organization_id\":\"1\"").getBytes(StandardCharsets.UTF_8);

        assertThat(service.ingest(tampered, signed, "evt_1")).isEqualTo(WebhookIngestResult.REJECTED);
        assertThat(service.ingest(body, null, "evt_1")).isEqualTo(WebhookIngestResult.REJECTED);
        verifyNoInteractions(events, publisher);
    }

    @Test
    void aVerifiedEventIsStoredUnderTheHeaderIdAndPublished() {
        byte[] body = RazorpayFixtures.body("subscription.activated");

        assertThat(service.ingest(body, RazorpayFixtures.signature(body), " evt_Header01 ")).isEqualTo(WebhookIngestResult.ACCEPTED);

        ArgumentCaptor<BillingEvent> saved = ArgumentCaptor.forClass(BillingEvent.class);
        verify(events).saveAndFlush(saved.capture());
        BillingEvent row = saved.getValue();
        assertThat(row.getProvider()).isEqualTo(ProviderId.RAZORPAY);
        assertThat(row.getProviderEventId()).isEqualTo("evt_Header01");
        assertThat(row.getEventType()).isEqualTo("SUBSCRIPTION_ACTIVATED");
        assertThat(row.getOrganizationId()).isEqualTo(4242L);
        assertThat(row.getProviderSubscriptionId()).isEqualTo("sub_FixtureSub00001");
        assertThat(row.getStatus()).isEqualTo(BillingEventStatus.RECEIVED);
        assertThat(row.getSignatureVerified()).isTrue();
        assertThat(row.getPayload().getBytes(StandardCharsets.UTF_8)).isEqualTo(body);
        verify(publisher).publishEvent(new BillingEventReceived(77L));
    }

    @Test
    void withoutTheHeaderTheBodyDigestIsTheId() {
        byte[] body = RazorpayFixtures.body("subscription.charged");
        service.ingest(body, RazorpayFixtures.signature(body), null);

        ArgumentCaptor<BillingEvent> saved = ArgumentCaptor.forClass(BillingEvent.class);
        verify(events).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getProviderEventId()).hasSize(64)
                .isEqualTo(new RazorpayEventParser().parse(body).getFirst().providerEventId());
    }

    @Test
    void aSecondDeliveryOfTheSameEventIsADuplicateAndNothingIsPublished() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        when(events.findByProviderAndProviderEventId(ProviderId.RAZORPAY, "evt_dup")).thenReturn(Optional.of(new BillingEvent()));

        assertThat(service.ingest(body, RazorpayFixtures.signature(body), "evt_dup")).isEqualTo(WebhookIngestResult.DUPLICATE);
        verify(events, never()).saveAndFlush(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void aRaceOnTheUniqueKeyIsAlsoADuplicate() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        when(events.saveAndFlush(any(BillingEvent.class))).thenThrow(new DataIntegrityViolationException("uk_billing_event_provider_event"));

        assertThat(service.ingest(body, RazorpayFixtures.signature(body), "evt_race")).isEqualTo(WebhookIngestResult.DUPLICATE);
        verifyNoInteractions(publisher);
    }

    @Test
    void aVerifiedBodyThatIsNotAnEventIsUnparseable() {
        byte[] body = "{\"entity\":\"payment\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(service.ingest(body, RazorpayFixtures.signature(body), "evt_x")).isEqualTo(WebhookIngestResult.UNPARSEABLE);
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void underProviderNoneEveryDeliveryIsRejected() {
        BillingWebhookService none = new BillingWebhookService(new NoOpBillingGateway(), events, publisher);
        byte[] body = RazorpayFixtures.body("subscription.activated");

        assertThat(none.ingest(body, RazorpayFixtures.signature(body), "evt_1")).isEqualTo(WebhookIngestResult.REJECTED);
        verifyNoInteractions(events, publisher);
    }

    @Test
    void theControllerMapsOutcomesToStatusCodes() {
        BillingWebhookService mocked = mock(BillingWebhookService.class);
        BillingWebhookController controller = new BillingWebhookController(mocked);
        byte[] body = {1, 2, 3};
        when(mocked.ingest(eq(body), eq("sig"), eq("id"))).thenReturn(WebhookIngestResult.ACCEPTED,
                WebhookIngestResult.DUPLICATE, WebhookIngestResult.REJECTED, WebhookIngestResult.UNPARSEABLE);

        assertThat(controller.razorpay(body, "sig", "id").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.razorpay(body, "sig", "id").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.razorpay(body, "sig", "id").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.razorpay(body, "sig", "id").getStatusCode().value()).isEqualTo(400);
    }
}

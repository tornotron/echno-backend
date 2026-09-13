package org.tornotron.echno_backend.billing.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.billing.Feature;
import org.tornotron.echno_backend.billing.entitlement.PastDueGracePolicy;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.PlanFeature;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.entitlement.BillingModuleEntitlementResolver;
import org.tornotron.echno_backend.billing.entitlement.EntitlementPolicy;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;
import org.tornotron.echno_backend.billing.gateway.MandatePolicy;
import org.tornotron.echno_backend.billing.gateway.NormalizedMandateStatus;
import org.tornotron.echno_backend.billing.gateway.PaymentMandate;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayBillingGateway;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayEventParser;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayFixtures;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayRestClient;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayWebhookSignature;
import org.tornotron.echno_backend.billing.repositories.BillingCustomerRepository;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.billing.repositories.GatewayPlanMappingRepository;
import org.tornotron.echno_backend.billing.repositories.PaymentMandateRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole path from a signed Razorpay body to the entitlement gate, against CockroachDB:
 * inbox dedupe, order tolerance, each event type's projection, and the gate flipping in
 * enforce mode. The fixtures are rewritten to the organization the test creates, then
 * signed, so the projector resolves a real row.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "echno.entitlement.mode=enforce")
@Import({BillingWebhookService.class, BillingEventProjector.class, EntitlementProjection.class,
        BillingReconciliationService.class, SubscriptionService.class, SubscriptionCache.class,
        TenantScopedJobRunner.class, EntitlementPolicy.class, PastDueGracePolicy.class, BillingModuleEntitlementResolver.class,
        WebhookProjectionIT.GatewayConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookProjectionIT extends AbstractIntegrationTest {

    static final String FEATURE = "MODULE_INSPECTIONS";
    static final String PLAN = "fixture-pro";
    static String fetchedSubscriptionJson = "{}";

    @TestConfiguration
    static class GatewayConfig {
        @Bean
        BillingGateway billingGateway(BillingCustomerRepository customers, GatewayPlanMappingRepository mappings, PlanRepository plans) {
            BillingGatewayProperties.Razorpay props = new BillingGatewayProperties.Razorpay();
            props.setKeyId("rzp_test_it");
            props.setKeySecret("secret");
            RazorpayRestClient stub = new RazorpayRestClient(props) {
                @Override
                public JsonNode get(String path) {
                    try {
                        return new ObjectMapper().readTree(fetchedSubscriptionJson);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            return new RazorpayBillingGateway(stub, new RazorpayWebhookSignature(RazorpayFixtures.WEBHOOK_SECRET),
                    new RazorpayEventParser(), new MandatePolicy(MandatePolicy.DEFAULT_AFA_CAP_PAISE), "INR",
                    customers, mappings, plans);
        }
    }

    @Autowired private BillingWebhookService webhook;
    @Autowired private BillingEventProjector projector;
    @Autowired private BillingReconciliationService reconciliation;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private BillingModuleEntitlementResolver entitlement;
    @Autowired private SubscriptionCache cache;
    @Autowired private BillingEventRepository events;
    @Autowired private SubscriptionRepository subscriptions;
    @Autowired private PaymentMandateRepository mandates;
    @Autowired private OrganizationRepository organizations;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    private Long orgId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        cache.evictAll();
        inCommittedTx(() -> {
            Organization organization = new Organization();
            organization.setOrganizationName("webhook-it-" + System.nanoTime());
            organization.setOrganizationAddress("1 Test Lane");
            organization.setOrganizationEmail("webhook-it@example.com");
            organization.setOrganizationPhone("+910000000000");
            orgId = organizations.save(organization).getId();
            Feature feature = (Feature) entityManager.createQuery("SELECT f FROM Feature f WHERE f.code = :code")
                    .setParameter("code", FEATURE).getResultStream().findFirst().orElseGet(() -> {
                        Feature created = Feature.builder().code(FEATURE).name("Inspections module")
                                .featureType(FeatureType.BOOLEAN).isActive(true).build();
                        entityManager.persist(created);
                        return created;
                    });
            if (entityManager.createQuery("SELECT p FROM Plan p WHERE p.code = :code").setParameter("code", PLAN)
                    .getResultList().isEmpty()) {
                Plan plan = Plan.builder().code(PLAN).name("Fixture Pro").monthlyPrice(new BigDecimal("4999.00"))
                        .annualPrice(new BigDecimal("49990.00")).isActive(true).isPublic(true).build();
                plan.addFeature(PlanFeature.builder().feature(feature).enabled(true).build());
                entityManager.persist(plan);
            }
            entityManager.flush();
        });
        TenantContext.declareUnscoped("webhook projection IT drives the projector directly, as the async listener would");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        cache.evictAll();
        inCommittedTx(() -> {
            entityManager.createNativeQuery("DELETE FROM billing_event WHERE provider = 'RAZORPAY'").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM payment_mandate WHERE organization_id = " + orgId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM subscription WHERE organization_id = " + orgId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM organization WHERE id = " + orgId).executeUpdate();
        });
    }

    @Test
    void anActivatedEventCreatesTheProjectionAndTheGateOpens() {
        assertThat(entitlement.isEntitled(orgId, FEATURE)).as("enforce mode, no subscription yet").isFalse();

        deliver("subscription.activated", "evt_act");

        Subscription row = projected();
        assertThat(row.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(row.getProvider()).isEqualTo(ProviderId.RAZORPAY);
        assertThat(row.getPlan().getCode()).isEqualTo(PLAN);
        assertThat(row.getCurrentPeriodStart()).isEqualTo(Instant.ofEpochSecond(1725148800L));
        assertThat(row.getCurrentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1727740800L));
        assertThat(inbox("evt_act").getStatus()).isEqualTo(BillingEventStatus.PROCESSED);
        assertThat(inbox("evt_act").getOrganizationId()).isEqualTo(orgId);
        // The fixture period is in the past relative to the clock, so the gate needs a live period.
        extendPeriod(row);
        assertThat(subscriptionService.checkFeatureAccess(orgId, FEATURE).isAllowed()).isTrue();
        assertThat(entitlement.isEntitled(orgId, FEATURE)).isTrue();
    }

    @Test
    void aDuplicateDeliveryIsAcknowledgedAndProjectsNothingTwice() {
        deliver("subscription.activated", "evt_dup");
        Subscription first = projected();

        assertThat(webhook.ingest(rewritten("subscription.activated"), sign(rewritten("subscription.activated")), "evt_dup"))
                .isEqualTo(WebhookIngestResult.DUPLICATE);

        assertThat(events.findAll().stream().filter(e -> "evt_dup".equals(e.getProviderEventId()))).hasSize(1);
        assertThat(subscriptions.findByOrganizationIdOrderByCreatedAtDesc(orgId)).hasSize(1);
        assertThat(projected().getId()).isEqualTo(first.getId());
    }

    @Test
    void aChargedThatArrivesBeforeItsActivatedIsNotUndoneByTheLateActivated() {
        deliver("subscription.charged", "evt_charged");
        Subscription afterCharged = projected();
        assertThat(afterCharged.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(afterCharged.getCurrentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1730419200L));

        deliver("subscription.activated", "evt_late_activated");

        assertThat(inbox("evt_late_activated").getStatus()).isEqualTo(BillingEventStatus.SKIPPED);
        assertThat(inbox("evt_late_activated").getLastError()).contains("Stale");
        Subscription converged = projected();
        assertThat(converged.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(converged.getCurrentPeriodEnd()).as("the charged period, not the earlier activation period")
                .isEqualTo(Instant.ofEpochSecond(1730419200L));
    }

    @Test
    void theLifecycleProjectsEachStatusAndTheGateFollowsIt() {
        deliver("subscription.authenticated", "evt_auth");
        assertThat(projected().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        deliver("subscription.activated", "evt_act");
        deliver("subscription.charged", "evt_charged");
        assertThat(projected().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        extendPeriod(projected());
        assertThat(entitlement.isEntitled(orgId, FEATURE)).isTrue();

        deliver("subscription.pending", "evt_pending");
        assertThat(projected().getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);

        deliver("subscription.halted", "evt_halted");
        assertThat(projected().getStatus()).isEqualTo(SubscriptionStatus.UNPAID);
        assertThat(entitlement.isEntitled(orgId, FEATURE)).as("halted revokes").isFalse();
    }

    @Test
    void inEnforceModeACancelledOrganizationLosesTheModule() {
        deliver("subscription.activated", "evt_act");
        extendPeriod(projected());
        assertThat(entitlement.isEntitled(orgId, FEATURE)).isTrue();
        assertThat(subscriptionService.checkFeatureAccess(orgId, FEATURE).isAllowed()).isTrue();

        deliver("subscription.cancelled", "evt_cancel");

        Subscription row = projected();
        assertThat(row.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(row.getCanceledAt()).isEqualTo(Instant.ofEpochSecond(1728000001L));
        assertThat(subscriptionService.checkFeatureAccess(orgId, FEATURE).isAllowed()).as("cache evicted on the transition").isFalse();
        assertThat(entitlement.isEntitled(orgId, FEATURE)).isFalse();
    }

    @Test
    void aFailedPaymentMovesALiveSubscriptionToPastDue() {
        deliver("subscription.activated", "evt_act");
        deliver("payment.failed", "evt_payfail");

        assertThat(projected().getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(inbox("evt_payfail").getStatus()).isEqualTo(BillingEventStatus.PROCESSED);
    }

    @Test
    void aConfirmedTokenRecordsTheMandate() {
        deliver("token.confirmed", "evt_token");

        List<PaymentMandate> recorded = inCommittedTx(() -> mandates.findAll().stream()
                .filter(m -> "token_FixtureTok0001".equals(m.getProviderMandateRef())).toList());
        assertThat(recorded).hasSize(1);
        PaymentMandate mandate = recorded.getFirst();
        assertThat(mandate.getStatus()).isEqualTo(NormalizedMandateStatus.AUTHORIZED);
        assertThat(mandate.getMaxAmountPaise()).isEqualTo(1_500_000L);
        assertThat(mandate.getAuthorizedAt()).isNotNull();
        assertThat(inbox("evt_token").getStatus()).isEqualTo(BillingEventStatus.PROCESSED);
    }

    @Test
    void aGatewayActivationSupersedesTheManualSubscription() {
        Instant now = Instant.now();
        inCommittedTx(() -> {
            Plan plan = (Plan) entityManager.createQuery("SELECT p FROM Plan p WHERE p.code = :code").setParameter("code", PLAN).getSingleResult();
            entityManager.persist(Subscription.builder().organizationId(orgId).plan(plan).status(SubscriptionStatus.TRIALING)
                    .currentPeriodStart(now).currentPeriodEnd(now.plus(10, ChronoUnit.DAYS)).build());
        });
        assertThat(subscriptionService.getActiveSubscription(orgId)).isPresent();

        deliver("subscription.activated", "evt_act");

        List<Subscription> all = subscriptions.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        assertThat(all).hasSize(2);
        Subscription manual = all.stream().filter(s -> s.getProvider() == ProviderId.MANUAL).findFirst().orElseThrow();
        assertThat(manual.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(manual.getCancellationReason()).contains("Superseded by RAZORPAY subscription sub_FixtureSub00001");
    }

    @Test
    void reconciliationRepairsAProjectionFromTheProvidersCurrentState() {
        deliver("subscription.activated", "evt_act");
        fetchedSubscriptionJson = "{\"id\":\"sub_FixtureSub00001\",\"plan_id\":\"plan_FixturePlan001\",\"customer_id\":\"cust_FixtureCust001\","
                + "\"status\":\"paused\",\"current_start\":1727740800,\"current_end\":1730419200}";

        String outcome = reconciliation.reconcile("sub_FixtureSub00001");

        assertThat(outcome).contains("ACTIVE -> PAUSED");
        assertThat(projected().getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
    }

    @Test
    void anEventForAnUnknownOrganizationIsSkippedNotFailed() {
        byte[] body = new String(RazorpayFixtures.body("subscription.activated"), StandardCharsets.UTF_8)
                .replace("\"organization_id\":\"4242\"", "\"organization_id\":\"\"").getBytes(StandardCharsets.UTF_8);
        webhook.ingest(body, sign(body), "evt_noorg");
        projector.process(inbox("evt_noorg").getId());

        assertThat(inbox("evt_noorg").getStatus()).isEqualTo(BillingEventStatus.SKIPPED);
        assertThat(inbox("evt_noorg").getLastError()).contains("Organization could not be resolved");
        assertThat(subscriptions.findByOrganizationIdOrderByCreatedAtDesc(orgId)).isEmpty();
    }

    private void deliver(String fixture, String eventId) {
        byte[] body = rewritten(fixture);
        assertThat(webhook.ingest(body, sign(body), eventId)).isEqualTo(WebhookIngestResult.ACCEPTED);
        projector.process(inbox(eventId).getId());
    }

    private byte[] rewritten(String fixture) {
        return new String(RazorpayFixtures.body(fixture), StandardCharsets.UTF_8)
                .replace("\"organization_id\":\"4242\"", "\"organization_id\":\"" + orgId + "\"")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] body) {
        return RazorpayFixtures.signature(body);
    }

    private BillingEvent inbox(String eventId) {
        return events.findByProviderAndProviderEventId(ProviderId.RAZORPAY, eventId).orElseThrow();
    }

    private Subscription projected() {
        return inCommittedTx(() -> subscriptions.findByProviderAndExternalSubscriptionId(ProviderId.RAZORPAY, "sub_FixtureSub00001").orElseThrow());
    }

    /** The recorded fixtures date from 2024; the gate also checks the period, so push it past now. */
    private void extendPeriod(Subscription row) {
        inCommittedTx(() -> entityManager.createNativeQuery("UPDATE subscription SET current_period_end = :end WHERE id = :id")
                .setParameter("end", Instant.now().plus(30, ChronoUnit.DAYS)).setParameter("id", row.getId()).executeUpdate());
        cache.evictAll();
    }

    private void inCommittedTx(Runnable work) {
        inCommittedTx((Supplier<Void>) () -> {
            work.run();
            return null;
        });
    }

    private <T> T inCommittedTx(Supplier<T> work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt.execute(status -> work.get());
    }
}

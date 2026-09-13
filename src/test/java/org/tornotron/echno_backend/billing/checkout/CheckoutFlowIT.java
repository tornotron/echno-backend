package org.tornotron.echno_backend.billing.checkout;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.BillingEventSummaryDto;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionCreateDto;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.dto.VerifyCheckoutDto;
import org.tornotron.echno_backend.billing.entitlement.BillingModuleEntitlementResolver;
import org.tornotron.echno_backend.billing.entitlement.EntitlementPolicy;
import org.tornotron.echno_backend.billing.entitlement.PastDueGracePolicy;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;
import org.tornotron.echno_backend.billing.gateway.MandatePolicy;
import org.tornotron.echno_backend.billing.gateway.NormalizedEventType;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayCustomer;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayPlanRef;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.billing.repositories.CheckoutSessionRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.billing.webhook.EntitlementProjection;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * The checkout path against CockroachDB with the port stubbed: a session writes its row and
 * an INCOMPLETE projection row, verify activates that row through the projection and stays
 * idempotent, a webhook that activated first is left alone, and the history page never
 * shows another organization's events.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CheckoutService.class, EntitlementProjection.class, SubscriptionService.class, SubscriptionCache.class,
        TenantScopedJobRunner.class, EntitlementPolicy.class, PastDueGracePolicy.class, BillingModuleEntitlementResolver.class,
        CheckoutFlowIT.GatewayConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutFlowIT extends AbstractIntegrationTest {

    static final String PLAN = "checkout-it-pro";
    static final String SUB = "sub_checkout_it_1";

    @TestConfiguration
    static class GatewayConfig {
        @Bean
        BillingGatewayProperties billingGatewayProperties() {
            return new BillingGatewayProperties();
        }

        @Bean
        MandatePolicy mandatePolicy() {
            return new MandatePolicy(MandatePolicy.DEFAULT_AFA_CAP_PAISE);
        }

        @Bean
        BillingGateway billingGateway() {
            BillingGateway gateway = Mockito.mock(BillingGateway.class);
            Mockito.when(gateway.isEnabled()).thenReturn(true);
            Mockito.when(gateway.providerId()).thenReturn(ProviderId.RAZORPAY);
            Mockito.when(gateway.publicKeyId()).thenReturn("rzp_test_it");
            Mockito.when(gateway.ensureCustomer(any())).thenAnswer(inv -> new GatewayCustomer(1L, "cust_it"));
            Mockito.when(gateway.ensurePlan(any(), any())).thenReturn(new GatewayPlanRef(PLAN, BillingPeriod.MONTHLY, "plan_it"));
            Mockito.when(gateway.createSubscription(any())).thenReturn(new GatewaySubscription(SUB, "plan_it", "cust_it",
                    NormalizedSubscriptionStatus.CREATED, null, null, null, null, "https://rzp.io/i/it"));
            Mockito.when(gateway.verifyCheckoutSignature(any())).thenReturn(true);
            return gateway;
        }
    }

    @Autowired private CheckoutService checkout;
    @Autowired private EntitlementProjection projection;
    @Autowired private SubscriptionRepository subscriptions;
    @Autowired private CheckoutSessionRepository sessions;
    @Autowired private BillingEventRepository events;
    @Autowired private OrganizationRepository organizations;
    @Autowired private SubscriptionCache cache;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    private Long orgA;
    private Long orgB;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        cache.evictAll();
        inCommittedTx(() -> {
            orgA = organizations.save(organization("checkout-it-a-")).getId();
            orgB = organizations.save(organization("checkout-it-b-")).getId();
            if (entityManager.createQuery("SELECT p FROM Plan p WHERE p.code = :code").setParameter("code", PLAN)
                    .getResultList().isEmpty()) {
                entityManager.persist(Plan.builder().code(PLAN).name("Checkout IT Pro").monthlyPrice(new BigDecimal("4999.00"))
                        .annualPrice(new BigDecimal("49990.00")).isActive(true).isPublic(true).build());
            }
            entityManager.flush();
        });
        TenantContext.setCurrentOrgId(orgA);
    }

    private static Organization organization(String prefix) {
        Organization organization = new Organization();
        organization.setOrganizationName(prefix + System.nanoTime());
        organization.setOrganizationAddress("1 Test Lane");
        organization.setOrganizationEmail(prefix + "billing@example.com");
        organization.setOrganizationPhone("+910000000000");
        return organization;
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        cache.evictAll();
        inCommittedTx(() -> {
            for (Long org : List.of(orgA, orgB)) {
                entityManager.createNativeQuery("DELETE FROM billing_event WHERE organization_id = " + org).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM checkout_session WHERE organization_id = " + org).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM subscription WHERE organization_id = " + org).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM organization WHERE id = " + org).executeUpdate();
            }
        });
    }

    @Test
    void aSessionThenVerifyActivatesOnceAndStaysIdempotent() {
        CheckoutSessionDto session = checkout.createSession(orgA, null,
                CheckoutSessionCreateDto.builder().planCode(PLAN).billingPeriod(BillingPeriod.MONTHLY).build());

        assertThat(session.getProviderSubscriptionId()).isEqualTo(SUB);
        assertThat(session.getKeyId()).isEqualTo("rzp_test_it");
        CheckoutSession stored = inCommittedTx(() -> sessions.findById(session.getId()).orElseThrow());
        assertThat(stored.getStatus()).isEqualTo(CheckoutSessionStatus.OPEN);
        Subscription pending = projected();
        assertThat(pending.getStatus()).isEqualTo(SubscriptionStatus.INCOMPLETE);
        assertThat(pending.getOrganizationId()).isEqualTo(orgA);

        SubscriptionDto first = checkout.verify(orgA, verifyDto());
        assertThat(first.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(first.getProvider()).isEqualTo("RAZORPAY");
        assertThat(first.getProviderSubscriptionId()).isEqualTo(SUB);

        SubscriptionDto second = checkout.verify(orgA, verifyDto());
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(inCommittedTx(() -> subscriptions.findByOrganizationIdOrderByCreatedAtDesc(orgA))).hasSize(1);
        CheckoutSession verified = inCommittedTx(() -> sessions.findById(session.getId()).orElseThrow());
        assertThat(verified.getStatus()).isEqualTo(CheckoutSessionStatus.VERIFIED);
        assertThat(verified.getSubscriptionId()).isEqualTo(first.getId());
        assertThat(verified.getProviderPaymentId()).isEqualTo("pay_it_1");
    }

    @Test
    void aWebhookThatActivatedFirstIsLeftAloneByVerify() {
        checkout.createSession(orgA, null, CheckoutSessionCreateDto.builder().planCode(PLAN).billingPeriod(BillingPeriod.MONTHLY).build());
        Instant periodStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant periodEnd = Instant.now().plus(45, ChronoUnit.DAYS);
        projection.apply(orgA, new NormalizedBillingEvent(ProviderId.RAZORPAY, "evt_it_activated", NormalizedEventType.SUBSCRIPTION_ACTIVATED,
                Instant.now(), orgA, SUB, "cust_it", "plan_it", PLAN,
                new GatewaySubscription(SUB, "plan_it", "cust_it", NormalizedSubscriptionStatus.ACTIVE, periodStart, periodEnd, null, null, null),
                null, null, null, null));
        assertThat(projected().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        SubscriptionDto dto = checkout.verify(orgA, verifyDto());

        assertThat(dto.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        Subscription row = projected();
        assertThat(row.getCurrentPeriodEnd()).as("the webhook's period is kept").isEqualTo(periodEnd);
        assertThat(inCommittedTx(() -> subscriptions.findByOrganizationIdOrderByCreatedAtDesc(orgA))).hasSize(1);
    }

    @Test
    void theHistoryPageIsScopedToTheCallersOrganization() {
        inCommittedTx(() -> {
            events.save(event(orgA, "evt_a_1", "subscription.charged", "{\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_a\",\"amount\":499900,\"currency\":\"INR\"}}}}"));
            events.save(event(orgB, "evt_b_1", "subscription.charged", "{\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_b\",\"amount\":100,\"currency\":\"INR\"}}}}"));
            events.save(event(orgA, "evt_a_2", "payment.failed", "{}"));
        });

        List<BillingEventSummaryDto> page = checkout.listEvents(orgA);

        assertThat(page).extracting(BillingEventSummaryDto::getProviderEventId).containsExactlyInAnyOrder("evt_a_1", "evt_a_2");
        BillingEventSummaryDto charged = page.stream().filter(e -> "evt_a_1".equals(e.getProviderEventId())).findFirst().orElseThrow();
        assertThat(charged.getAmountPaise()).isEqualTo(499_900L);
        assertThat(charged.getReference()).isEqualTo("pay_a");
        assertThat(charged.getCurrency()).isEqualTo("INR");
        assertThat(checkout.listEvents(orgB)).extracting(BillingEventSummaryDto::getProviderEventId).containsExactly("evt_b_1");
    }

    private static BillingEvent event(Long org, String id, String type, String payload) {
        return BillingEvent.builder().provider(ProviderId.RAZORPAY).providerEventId(id).eventType(type).organizationId(org)
                .payload(payload).signatureVerified(true).status(BillingEventStatus.PROCESSED).receivedAt(Instant.now())
                .occurredAt(Instant.now()).build();
    }

    private static VerifyCheckoutDto verifyDto() {
        return VerifyCheckoutDto.builder().providerPaymentId("pay_it_1").providerSubscriptionId(SUB).providerSignature("stubbed").build();
    }

    private Subscription projected() {
        return inCommittedTx(() -> subscriptions.findByProviderAndExternalSubscriptionId(ProviderId.RAZORPAY, SUB).orElseThrow());
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

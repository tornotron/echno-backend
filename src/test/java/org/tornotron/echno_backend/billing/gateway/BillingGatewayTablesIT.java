package org.tornotron.echno_backend.billing.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.repositories.BillingCustomerRepository;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.billing.repositories.GatewayPlanMappingRepository;
import org.tornotron.echno_backend.billing.repositories.PaymentMandateRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The changeset 112 tables against a real CockroachDB, with Hibernate validating the mapping:
 * the inbox unique key that is the idempotency guard, the org-scoped rows, and the provider
 * column on subscription defaulting to MANUAL for everything that exists today.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingGatewayTablesIT extends AbstractIntegrationTest {

    @Autowired
    private BillingEventRepository events;
    @Autowired
    private BillingCustomerRepository customers;
    @Autowired
    private PaymentMandateRepository mandates;
    @Autowired
    private GatewayPlanMappingRepository planMappings;
    @Autowired
    private SubscriptionRepository subscriptions;
    @Autowired
    private PlanRepository plans;
    @Autowired
    private OrganizationRepository organizations;

    private Organization organization;

    @BeforeEach
    void organization() {
        TenantContext.declareUnscoped("test setup");
        organization = new Organization();
        organization.setOrganizationName("gateway-tables-it-" + System.nanoTime());
        organization.setOrganizationAddress("1 Test Lane");
        organization.setOrganizationEmail("gateway-it@example.com");
        organization.setOrganizationPhone("+910000000000");
        organization = organizations.save(organization);
        TenantContext.clear();
        TenantContext.setCurrentOrgId(organization.getId());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void theInboxRefusesASecondRowForTheSameProviderEvent() {
        events.save(event("evt_dup_" + System.nanoTime()));
        BillingEvent duplicate = event(events.findAll().getLast().getProviderEventId());

        assertThatThrownBy(() -> events.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameEventIdUnderAnotherProviderIsADifferentRow() {
        String id = "evt_shared_" + System.nanoTime();
        events.save(event(id));
        BillingEvent manual = event(id);
        manual.setProvider(ProviderId.MANUAL);

        assertThat(events.saveAndFlush(manual).getId()).isNotNull();
        assertThat(events.findByProviderAndProviderEventId(ProviderId.RAZORPAY, id)).isPresent();
        assertThat(events.findByProviderAndProviderEventId(ProviderId.MANUAL, id)).isPresent();
    }

    @Test
    void customerMandateAndPlanMappingRowsRoundTrip() {
        BillingCustomer customer = customers.save(BillingCustomer.builder()
                .organization(organization).provider(ProviderId.RAZORPAY).providerCustomerId("cust_it_" + organization.getId()).build());
        PaymentMandate mandate = mandates.save(PaymentMandate.builder()
                .organization(organization).provider(ProviderId.RAZORPAY).providerMandateRef("token_it_" + organization.getId())
                .method(MandateMethod.UPI_AUTOPAY).status(NormalizedMandateStatus.AUTHORIZED).maxAmountPaise(1_500_000L)
                .authorizedAt(Instant.now()).build());
        GatewayPlanMapping mapping = planMappings.save(GatewayPlanMapping.builder()
                .planCode("it-plan").provider(ProviderId.RAZORPAY).billingInterval(BillingPeriod.MONTHLY)
                .providerPlanId("plan_it_" + organization.getId()).amountPaise(499_900L).build());

        assertThat(customers.findByProviderAndProviderCustomerId(ProviderId.RAZORPAY, customer.getProviderCustomerId()))
                .isPresent().get().extracting(c -> c.getOrganization().getId()).isEqualTo(organization.getId());
        assertThat(mandates.findByProviderAndProviderMandateRef(ProviderId.RAZORPAY, mandate.getProviderMandateRef()))
                .isPresent().get().extracting(PaymentMandate::getStatus).isEqualTo(NormalizedMandateStatus.AUTHORIZED);
        assertThat(planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(ProviderId.RAZORPAY, "it-plan", BillingPeriod.MONTHLY))
                .isPresent().get().extracting(GatewayPlanMapping::getProviderPlanId).isEqualTo(mapping.getProviderPlanId());
        assertThat(customer.getCreatedAt()).isNotNull();
    }

    @Test
    void aSubscriptionWrittenTodayIsManual() {
        Plan plan = plans.save(Plan.builder().code("it-manual-" + organization.getId()).name("Manual")
                .monthlyPrice(new BigDecimal("10.00")).build());
        Subscription saved = subscriptions.save(Subscription.builder()
                .organizationId(organization.getId()).plan(plan).status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(Instant.now()).currentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS)).build());

        assertThat(subscriptions.findById(saved.getId())).isPresent().get()
                .extracting(Subscription::getProvider).isEqualTo(ProviderId.MANUAL);
    }

    private static BillingEvent event(String providerEventId) {
        return BillingEvent.builder()
                .provider(ProviderId.RAZORPAY)
                .providerEventId(providerEventId)
                .eventType("subscription.activated")
                .payload("{}")
                .signatureVerified(true)
                .receivedAt(Instant.now())
                .build();
    }
}

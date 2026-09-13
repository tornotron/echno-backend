package org.tornotron.echno_backend.billing.gateway.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.BillingCustomer;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.organization.Organization;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;
import org.tornotron.echno_backend.billing.gateway.GatewayPlanMapping;
import org.tornotron.echno_backend.billing.gateway.MandatePolicy;
import org.tornotron.echno_backend.billing.gateway.MandatePolicyViolationException;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.ChangePlanCommand;
import org.tornotron.echno_backend.billing.gateway.dto.CreateSubscriptionCommand;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayCustomer;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayPlanRef;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NotifyInfo;
import org.tornotron.echno_backend.billing.gateway.dto.OrgBillingProfile;
import org.tornotron.echno_backend.billing.repositories.BillingCustomerRepository;
import org.tornotron.echno_backend.billing.repositories.GatewayPlanMappingRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the adapter against a recording HTTP layer: what it posts to Razorpay, what it
 * writes to the mapping tables, and which requests the RBI rules stop before any call.
 */
class RazorpayBillingGatewayTest {

    private static final Long ORG = 4242L;
    private static final NotifyInfo NOTIFY = new NotifyInfo("buyer@example.com", null);

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> calls = new ArrayList<>();
    private final List<Map<String, Object>> bodies = new ArrayList<>();
    private final BillingCustomerRepository customers = mock(BillingCustomerRepository.class);
    private final GatewayPlanMappingRepository planMappings = mock(GatewayPlanMappingRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private RazorpayBillingGateway gateway;
    private String nextResponse = "{\"id\":\"x\"}";

    @BeforeEach
    void wire() {
        BillingGatewayProperties.Razorpay props = new BillingGatewayProperties.Razorpay();
        props.setKeyId("rzp_test_key");
        props.setKeySecret("secret");
        RazorpayRestClient recording = new RazorpayRestClient(props) {
            @Override
            public JsonNode post(String path, Map<String, Object> body) {
                calls.add("POST " + path);
                bodies.add(body);
                return read(nextResponse);
            }

            @Override
            public JsonNode patch(String path, Map<String, Object> body) {
                calls.add("PATCH " + path);
                bodies.add(body);
                return read(nextResponse);
            }

            @Override
            public JsonNode get(String path) {
                calls.add("GET " + path);
                return read(nextResponse);
            }
        };
        gateway = new RazorpayBillingGateway(recording, new RazorpayWebhookSignature(RazorpayFixtures.WEBHOOK_SECRET),
                new RazorpayEventParser(), new MandatePolicy(MandatePolicy.DEFAULT_AFA_CAP_PAISE), "INR",
                customers, planMappings, plans);
        when(customers.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(planMappings.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void ensureCustomerReusesTheMappingAndOnlyCreatesOnce() {
        when(customers.findByOrganizationIdAndProvider(ORG, ProviderId.RAZORPAY)).thenReturn(Optional.empty());
        nextResponse = "{\"id\":\"cust_New001\",\"entity\":\"customer\"}";

        GatewayCustomer created = gateway.ensureCustomer(new OrgBillingProfile(ORG, "Acme Builders", "29ABCDE1234F1Z5",
                "billing@acme.example", "+919999999999", "INR"));

        assertThat(created.providerCustomerId()).isEqualTo("cust_New001");
        assertThat(calls).containsExactly("POST /customers");
        assertThat(bodies.getFirst()).containsEntry("name", "Acme Builders").containsEntry("gst_number", "29ABCDE1234F1Z5")
                .containsEntry("fail_existing", "0");
        assertThat(bodies.getFirst().get("notes")).isEqualTo(Map.of("organization_id", "4242"));
        verify(customers).save(any(BillingCustomer.class));

        when(customers.findByOrganizationIdAndProvider(ORG, ProviderId.RAZORPAY)).thenReturn(Optional.of(
                BillingCustomer.builder().provider(ProviderId.RAZORPAY).providerCustomerId("cust_New001").build()));
        GatewayCustomer reused = gateway.ensureCustomer(new OrgBillingProfile(ORG, "Acme", null, null, null, "INR"));
        assertThat(reused.providerCustomerId()).isEqualTo("cust_New001");
        assertThat(calls).hasSize(1);
    }

    @Test
    void ensurePlanSendsPaiseAndRecordsTheCurrentMapping() {
        when(planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(ProviderId.RAZORPAY, "pro", BillingPeriod.MONTHLY))
                .thenReturn(Optional.empty());
        nextResponse = "{\"id\":\"plan_New001\",\"entity\":\"plan\"}";

        GatewayPlanRef ref = gateway.ensurePlan(plan("pro", "4999.00", "49990.00"), BillingPeriod.MONTHLY);

        assertThat(ref).isEqualTo(new GatewayPlanRef("pro", BillingPeriod.MONTHLY, "plan_New001"));
        assertThat(calls).containsExactly("POST /plans");
        Map<String, Object> body = bodies.getFirst();
        assertThat(body).containsEntry("period", "monthly").containsEntry("interval", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) body.get("item");
        assertThat(item).containsEntry("amount", 499_900L).containsEntry("currency", "INR").containsEntry("name", "Pro");
        verify(planMappings).save(any(GatewayPlanMapping.class));
    }

    @Test
    void ensurePlanRetiresTheMappingWhenThePriceMovesAndCreatesAFreshRazorpayPlan() {
        GatewayPlanMapping stale = mapping("pro", BillingPeriod.MONTHLY);
        when(planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(ProviderId.RAZORPAY, "pro", BillingPeriod.MONTHLY))
                .thenReturn(Optional.of(stale));
        nextResponse = "{\"id\":\"plan_New002\"}";

        GatewayPlanRef unchanged = gateway.ensurePlan(plan("pro", "4999.00", null), BillingPeriod.MONTHLY);
        assertThat(unchanged.providerPlanId()).isEqualTo("plan_New001");
        assertThat(calls).isEmpty();

        GatewayPlanRef repriced = gateway.ensurePlan(plan("pro", "5999.00", null), BillingPeriod.MONTHLY);
        assertThat(repriced.providerPlanId()).isEqualTo("plan_New002");
        assertThat(stale.getIsCurrent()).isFalse();
        assertThat(calls).containsExactly("POST /plans");
    }

    @Test
    void changePlanRunsTheRbiRulesAgainstTheTargetPlan() {
        GatewayPlanMapping enterprise = GatewayPlanMapping.builder().planCode("enterprise").provider(ProviderId.RAZORPAY)
                .billingInterval(BillingPeriod.MONTHLY).providerPlanId("plan_Ent001").amountPaise(2_500_000L).build();
        when(planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(ProviderId.RAZORPAY, "enterprise", BillingPeriod.MONTHLY))
                .thenReturn(Optional.of(enterprise));

        assertThatThrownBy(() -> gateway.changePlan(new ChangePlanCommand(ORG, "sub_X", "enterprise", BillingPeriod.MONTHLY, 1, true, false)))
                .isInstanceOf(MandatePolicyViolationException.class);
        assertThat(calls).isEmpty();

        nextResponse = "{\"id\":\"sub_X\",\"status\":\"active\"}";
        gateway.changePlan(new ChangePlanCommand(ORG, "sub_X", "enterprise", BillingPeriod.MONTHLY, 1, true, true));
        assertThat(calls).containsExactly("PATCH /subscriptions/sub_X");
        assertThat(bodies.getFirst()).containsEntry("plan_id", "plan_Ent001").containsEntry("schedule_change_at", "cycle_end");
    }

    @Test
    void aPlainHttpBaseUrlIsRefusedBecauseCredentialsRideOnEveryRequest() {
        BillingGatewayProperties.Razorpay insecure = new BillingGatewayProperties.Razorpay();
        insecure.setBaseUrl("http://api.razorpay.com/v1");
        assertThatThrownBy(() -> new RazorpayRestClient(insecure)).isInstanceOf(IllegalArgumentException.class);
        BillingGatewayProperties.Razorpay local = new BillingGatewayProperties.Razorpay();
        local.setBaseUrl("http://localhost:9999");
        assertThatCode(() -> new RazorpayRestClient(local)).doesNotThrowAnyException();
    }

    @Test
    void ensurePlanRefusesAPlanWithNoPriceOnThatInterval() {
        when(planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(any(), any(), any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> gateway.ensurePlan(plan("free", null, null), BillingPeriod.ANNUAL))
                .isInstanceOf(BillingGatewayException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void createSubscriptionPostsTheMandateFieldsAndStampsTheOrganization() {
        mappedCustomerAndPlan("pro", "4999.00");
        nextResponse = "{\"id\":\"sub_New001\",\"entity\":\"subscription\",\"plan_id\":\"plan_New001\",\"customer_id\":\"cust_New001\","
                + "\"status\":\"created\",\"short_url\":\"https://rzp.io/i/new\",\"current_start\":null,\"current_end\":null}";

        GatewaySubscription created = gateway.createSubscription(command("pro", 1, 0, false));

        assertThat(created.providerSubscriptionId()).isEqualTo("sub_New001");
        assertThat(created.status()).isEqualTo(NormalizedSubscriptionStatus.CREATED);
        assertThat(created.authUrl()).isEqualTo("https://rzp.io/i/new");
        assertThat(calls).containsExactly("POST /subscriptions");
        Map<String, Object> body = bodies.getFirst();
        assertThat(body).containsEntry("plan_id", "plan_New001").containsEntry("customer_id", "cust_New001")
                .containsEntry("customer_notify", 1).containsEntry("total_count", 100).doesNotContainKey("start_at");
        assertThat(body.get("notes")).isEqualTo(Map.of("organization_id", "4242", "plan_code", "pro"));
    }

    @Test
    void aTrialBecomesAFutureStartAndAnnualCyclesAreCappedAtTen() {
        mappedCustomerAndPlan("pro", "4999.00");
        when(planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(ProviderId.RAZORPAY, "pro", BillingPeriod.ANNUAL))
                .thenReturn(Optional.of(mapping("pro", BillingPeriod.ANNUAL)));
        when(plans.findByCodeWithFeatures("pro")).thenReturn(Optional.of(plan("pro", "4999.00", "49990.00")));
        nextResponse = "{\"id\":\"sub_New002\",\"status\":\"created\"}";

        // 49,990 rupees a year is above the AFA cap, so the buyer has to accept per-charge authentication.
        gateway.createSubscription(new CreateSubscriptionCommand(ORG, "pro", BillingPeriod.ANNUAL, 1, 14, null, NOTIFY, true));

        assertThat(bodies.getFirst()).containsKey("start_at").containsEntry("total_count", 10);
    }

    @Test
    void aPlanAboveTheRbiCapNeverReachesRazorpayWithoutAcceptance() {
        mappedCustomerAndPlan("enterprise", "25000.00");

        assertThatThrownBy(() -> gateway.createSubscription(command("enterprise", 1, 0, false)))
                .isInstanceOf(MandatePolicyViolationException.class);
        assertThat(calls).isEmpty();

        nextResponse = "{\"id\":\"sub_New003\",\"status\":\"created\"}";
        gateway.createSubscription(command("enterprise", 1, 0, true));
        assertThat(calls).containsExactly("POST /subscriptions");
    }

    @Test
    void aSubscriptionWithoutANotificationChannelIsRefusedBeforeAnyCall() {
        mappedCustomerAndPlan("pro", "4999.00");
        CreateSubscriptionCommand noChannel = new CreateSubscriptionCommand(ORG, "pro", BillingPeriod.MONTHLY, 1, 0, null,
                new NotifyInfo(null, null), false);

        assertThatThrownBy(() -> gateway.createSubscription(noChannel)).isInstanceOf(MandatePolicyViolationException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void createSubscriptionNeedsTheCustomerAndPlanMappedFirst() {
        when(customers.findByOrganizationIdAndProvider(ORG, ProviderId.RAZORPAY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> gateway.createSubscription(command("pro", 1, 0, false)))
                .isInstanceOf(BillingGatewayException.class).hasMessageContaining("ensureCustomer");
        assertThat(calls).isEmpty();
        verify(plans, never()).findByCodeWithFeatures(any());
    }

    @Test
    void anExpiryOnTheCommandIsSentAsExpireBy() {
        mappedCustomerAndPlan("pro", "4999.00");
        nextResponse = "{\"id\":\"sub_New002\",\"status\":\"created\"}";
        Instant expireBy = Instant.now().plus(35, ChronoUnit.MINUTES);

        gateway.createSubscription(new CreateSubscriptionCommand(ORG, "pro", BillingPeriod.MONTHLY, 1, 0, null, NOTIFY, false, expireBy));

        assertThat(bodies.getFirst()).containsEntry("expire_by", expireBy.getEpochSecond());
        assertThat(bodies.getFirst()).doesNotContainKey("start_at");
    }

    @Test
    void aCustomerRazorpayAlreadyHoldsForAnotherOrganizationIsRefusedWithTheFix() {
        when(customers.findByOrganizationIdAndProvider(ORG, ProviderId.RAZORPAY)).thenReturn(Optional.empty());
        Organization other = new Organization();
        other.setId(999L);
        when(customers.findByProviderAndProviderCustomerId(ProviderId.RAZORPAY, "cust_Shared"))
                .thenReturn(Optional.of(BillingCustomer.builder().organization(other).provider(ProviderId.RAZORPAY).providerCustomerId("cust_Shared").build()));
        nextResponse = "{\"id\":\"cust_Shared\",\"entity\":\"customer\"}";

        assertThatThrownBy(() -> gateway.ensureCustomer(new OrgBillingProfile(ORG, "Acme", null, "shared@example.com", "+919999999999", "INR")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("distinct billing email");
        verify(customers, never()).save(any(BillingCustomer.class));
    }

    @Test
    void cancelReturnsTheProvidersAnswer() {
        nextResponse = "{\"id\":\"sub_X\",\"status\":\"active\",\"current_start\":1727740800,\"current_end\":1730419200}";
        GatewaySubscription answer = gateway.cancelSubscription("sub_X", true);
        assertThat(answer.status()).isEqualTo(NormalizedSubscriptionStatus.ACTIVE);
        assertThat(bodies.getFirst()).containsEntry("cancel_at_cycle_end", 1);
    }

    @Test
    void lifecycleCallsHitTheDocumentedEndpoints() {
        nextResponse = "{\"id\":\"sub_X\",\"status\":\"paused\"}";
        gateway.pauseSubscription("sub_X");
        gateway.resumeSubscription("sub_X");
        gateway.cancelSubscription("sub_X", true);
        GatewaySubscription fetched = gateway.fetchSubscription("sub_X");

        assertThat(calls).containsExactly("POST /subscriptions/sub_X/pause", "POST /subscriptions/sub_X/resume",
                "POST /subscriptions/sub_X/cancel", "GET /subscriptions/sub_X");
        assertThat(bodies.get(2)).containsEntry("cancel_at_cycle_end", 1);
        assertThat(fetched.status()).isEqualTo(NormalizedSubscriptionStatus.PAUSED);
    }

    @Test
    void webhookVerificationAndParsingGoThroughTheAdapter() {
        byte[] body = RazorpayFixtures.body("subscription.activated");
        assertThat(gateway.verifySignature(body, RazorpayFixtures.signature(body))).isTrue();
        assertThat(gateway.verifySignature(body, "deadbeef")).isFalse();
        assertThat(gateway.parseEvents(body)).singleElement()
                .satisfies(event -> assertThat(event.organizationId()).isEqualTo(ORG));
    }

    private void mappedCustomerAndPlan(String planCode, String monthly) {
        when(customers.findByOrganizationIdAndProvider(ORG, ProviderId.RAZORPAY)).thenReturn(Optional.of(
                BillingCustomer.builder().provider(ProviderId.RAZORPAY).providerCustomerId("cust_New001").build()));
        when(planMappings.findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(ProviderId.RAZORPAY, planCode, BillingPeriod.MONTHLY))
                .thenReturn(Optional.of(mapping(planCode, BillingPeriod.MONTHLY)));
        when(plans.findByCodeWithFeatures(eq(planCode))).thenReturn(Optional.of(plan(planCode, monthly, null)));
    }

    private static GatewayPlanMapping mapping(String planCode, BillingPeriod interval) {
        return GatewayPlanMapping.builder().planCode(planCode).provider(ProviderId.RAZORPAY)
                .billingInterval(interval).providerPlanId("plan_New001").amountPaise(499_900L).build();
    }

    private static CreateSubscriptionCommand command(String planCode, int quantity, int trialDays, boolean acceptAfa) {
        return new CreateSubscriptionCommand(ORG, planCode, BillingPeriod.MONTHLY, quantity, trialDays, null, NOTIFY, acceptAfa);
    }

    private static Plan plan(String code, String monthly, String annual) {
        return Plan.builder().code(code).name("Pro").currency("INR")
                .monthlyPrice(monthly == null ? null : new BigDecimal(monthly))
                .annualPrice(annual == null ? null : new BigDecimal(annual))
                .build();
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}

package org.tornotron.echno_backend.billing.checkout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.BillingMapper;
import org.tornotron.echno_backend.billing.dto.BillingProviderInfoDto;
import org.tornotron.echno_backend.billing.dto.CheckoutMandateTermsDto;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionCreateDto;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionDto;
import org.tornotron.echno_backend.billing.dto.MandateAcknowledgeDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.dto.VerifyCheckoutDto;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;
import org.tornotron.echno_backend.billing.gateway.BillingNotConfiguredException;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;
import org.tornotron.echno_backend.billing.gateway.MandatePolicy;
import org.tornotron.echno_backend.billing.gateway.MandatePolicyViolationException;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.CreateSubscriptionCommand;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayCustomer;
import org.tornotron.echno_backend.billing.gateway.dto.GatewayPlanRef;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.billing.repositories.CheckoutSessionRepository;
import org.tornotron.echno_backend.billing.repositories.PaymentMandateRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.billing.webhook.EntitlementProjection;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The checkout contract the web drives (#803): the provider answer under NONE, what a session
 * asks the port for and persists, the free-plan short cut, the mandate rules, and a verify that
 * activates once and defers to a webhook that got there first.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutServiceTest {

    private static final long ORG = 42L;
    private static final long USER = 7L;

    @Mock private BillingGateway gateway;
    @Mock private PlanRepository plans;
    @Mock private SubscriptionRepository subscriptions;
    @Mock private CheckoutSessionRepository sessions;
    @Mock private PaymentMandateRepository mandates;
    @Mock private BillingEventRepository events;
    @Mock private OrganizationRepository organizations;
    @Mock private SubscriptionService subscriptionService;
    @Mock private EntitlementProjection projection;
    @Mock private SubscriptionCache cache;
    @Mock private jakarta.persistence.EntityManager entityManager;

    private CheckoutService service;
    private Organization organization;

    @BeforeEach
    void setUp() {
        BillingGatewayProperties properties = new BillingGatewayProperties();
        service = new CheckoutService(gateway, properties, new MandatePolicy(MandatePolicy.DEFAULT_AFA_CAP_PAISE), plans,
                subscriptions, sessions, mandates, events, organizations, subscriptionService, projection, cache, entityManager);
        organization = new Organization();
        organization.setId(ORG);
        organization.setOrganizationName("Acme Builders");
        organization.setOrganizationEmail("billing@acme.example");
        organization.setOrganizationPhone("+919999999999");
        when(organizations.findById(ORG)).thenReturn(Optional.of(organization));
        when(sessions.save(any())).thenAnswer(inv -> {
            CheckoutSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(17L);
            return s;
        });
        when(subscriptions.save(any())).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            if (s.getId() == null) s.setId(101L);
            return s;
        });
    }

    private void razorpayWired() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.providerId()).thenReturn(ProviderId.RAZORPAY);
        when(gateway.publicKeyId()).thenReturn("rzp_test_abc");
        when(gateway.ensureCustomer(any())).thenReturn(new GatewayCustomer(ORG, "cust_1"));
        when(gateway.ensurePlan(any(), any())).thenAnswer(inv ->
                new GatewayPlanRef(((Plan) inv.getArgument(0)).getCode(), inv.getArgument(1), "plan_rzp_1"));
        when(gateway.createSubscription(any())).thenReturn(new GatewaySubscription("sub_1", "plan_rzp_1", "cust_1",
                NormalizedSubscriptionStatus.CREATED, null, null, null, null, "https://rzp.io/i/abc"));
    }

    private static Plan plan(String code, String monthly, String annual, int trialDays) {
        return Plan.builder().id(3L).code(code).name(code).monthlyPrice(new BigDecimal(monthly))
                .annualPrice(new BigDecimal(annual)).currency("INR").trialDays(trialDays).build();
    }

    private static CheckoutSession openSession(String subId, Plan plan, BillingPeriod period) {
        Organization org = new Organization();
        org.setId(ORG);
        return CheckoutSession.builder().id(17L).organization(org).provider(ProviderId.RAZORPAY).planCode(plan.getCode())
                .billingPeriod(period).providerSubscriptionId(subId).amountPaise(999_900L).currency("INR")
                .status(CheckoutSessionStatus.OPEN).expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
    }

    private static Subscription row(Plan plan, SubscriptionStatus status, String subId) {
        Instant now = Instant.now();
        return Subscription.builder().id(101L).organizationId(ORG).plan(plan).status(status).provider(ProviderId.RAZORPAY)
                .externalSubscriptionId(subId).currentPeriodStart(now).currentPeriodEnd(now.plus(30, ChronoUnit.DAYS)).build();
    }

    // -- provider ----------------------------------------------------------------------------

    @Test
    void providerNoneIsAWellFormedNotConfiguredAnswer() {
        when(gateway.isEnabled()).thenReturn(false);
        when(gateway.providerId()).thenReturn(ProviderId.MANUAL);

        BillingProviderInfoDto info = service.providerInfo();

        assertThat(info.getProvider()).isEqualTo("NONE");
        assertThat(info.isEnabled()).isFalse();
        assertThat(info.getKeyId()).isNull();
        assertThat(info.getCurrency()).isEqualTo("INR");
        assertThat(info.getAfaCapPaise()).isEqualTo(1_500_000L);
        assertThat(info.getPreDebitNoticeHours()).isEqualTo(24);
        assertThat(info.getSupportedFlows()).isEmpty();
    }

    @Test
    void providerRazorpayExposesThePublicKeyOnly() {
        razorpayWired();

        BillingProviderInfoDto info = service.providerInfo();

        assertThat(info.getProvider()).isEqualTo("RAZORPAY");
        assertThat(info.isEnabled()).isTrue();
        assertThat(info.getKeyId()).isEqualTo("rzp_test_abc");
        assertThat(info.getSupportedFlows()).containsExactly("SUBSCRIPTION");
    }

    // -- sessions ----------------------------------------------------------------------------

    @Test
    void aPaidPlanSessionCallsThePortWithThePlanMappingAndPersistsTheSessionAndAnIncompleteRow() {
        razorpayWired();
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(pro));
        when(subscriptions.findActiveSubscription(ORG)).thenReturn(Optional.empty());
        when(subscriptions.findByProviderAndExternalSubscriptionId(ProviderId.RAZORPAY, "sub_1")).thenReturn(Optional.empty());
        when(sessions.findFirstByOrganization_IdAndPlanCodeAndBillingPeriodAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(ORG), eq("PRO"), eq(BillingPeriod.MONTHLY), eq(CheckoutSessionStatus.OPEN), any())).thenReturn(Optional.empty());

        CheckoutSessionDto dto = service.createSession(ORG, USER,
                CheckoutSessionCreateDto.builder().planCode("PRO").billingPeriod(BillingPeriod.MONTHLY).build());

        verify(gateway).ensureCustomer(any());
        verify(gateway).ensurePlan(pro, BillingPeriod.MONTHLY);
        ArgumentCaptor<CreateSubscriptionCommand> cmd = ArgumentCaptor.forClass(CreateSubscriptionCommand.class);
        verify(gateway).createSubscription(cmd.capture());
        assertThat(cmd.getValue().organizationId()).isEqualTo(ORG);
        assertThat(cmd.getValue().planCode()).isEqualTo("PRO");
        assertThat(cmd.getValue().interval()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(cmd.getValue().notifyInfo().email()).isEqualTo("billing@acme.example");

        ArgumentCaptor<CheckoutSession> saved = ArgumentCaptor.forClass(CheckoutSession.class);
        verify(sessions).save(saved.capture());
        assertThat(saved.getValue().getProviderSubscriptionId()).isEqualTo("sub_1");
        assertThat(saved.getValue().getStatus()).isEqualTo(CheckoutSessionStatus.OPEN);
        assertThat(saved.getValue().getAmountPaise()).isEqualTo(999_900L);
        assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now());

        ArgumentCaptor<Subscription> rowSaved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptions).save(rowSaved.capture());
        assertThat(rowSaved.getValue().getStatus()).isEqualTo(SubscriptionStatus.INCOMPLETE);
        assertThat(rowSaved.getValue().getProvider()).isEqualTo(ProviderId.RAZORPAY);
        assertThat(rowSaved.getValue().getExternalSubscriptionId()).isEqualTo("sub_1");

        assertThat(dto.getProvider()).isEqualTo("RAZORPAY");
        assertThat(dto.getKeyId()).isEqualTo("rzp_test_abc");
        assertThat(dto.getProviderSubscriptionId()).isEqualTo("sub_1");
        assertThat(dto.getProviderOrderId()).isNull();
        assertThat(dto.isRecurring()).isTrue();
        assertThat(dto.getAmountPaise()).isEqualTo(999_900L);
        assertThat(dto.getAuthUrl()).isEqualTo("https://rzp.io/i/abc");
        assertThat(dto.getMandate()).isNotNull();
        assertThat(dto.getMandate().getAmountCapPaise()).isEqualTo(999_900L);
        assertThat(dto.getMandate().getPreDebitNoticeHours()).isEqualTo(24);
        assertThat(dto.getMandate().isPerChargeApproval()).isFalse();
        verify(projection, never()).apply(anyLong(), any());
    }

    @Test
    void aFreePlanActivatesDirectlyAndNeverTouchesTheGateway() {
        when(gateway.isEnabled()).thenReturn(false);
        Plan free = plan("FREE", "0.00", "0.00", 0);
        when(plans.findByCodeWithFeatures("FREE")).thenReturn(Optional.of(free));
        SubscriptionDto activated = SubscriptionDto.builder().id(55L).status(SubscriptionStatus.ACTIVE).build();
        when(subscriptionService.createSubscription(ORG, USER, "FREE", BillingPeriod.MONTHLY)).thenReturn(activated);

        CheckoutSessionDto dto = service.createSession(ORG, USER, CheckoutSessionCreateDto.builder().planCode("FREE").build());

        assertThat(dto.getProvider()).isEqualTo("NONE");
        assertThat(dto.getSubscription()).isSameAs(activated);
        assertThat(dto.getAmountPaise()).isZero();
        verify(gateway, never()).ensureCustomer(any());
        verify(gateway, never()).createSubscription(any());
        verify(sessions, never()).save(any());
    }

    @Test
    void aPaidPlanUnderProviderNoneIsRefusedAsNotConfigured() {
        when(gateway.isEnabled()).thenReturn(false);
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(plan("PRO", "9999.00", "99990.00", 0)));

        assertThatThrownBy(() -> service.createSession(ORG, USER, CheckoutSessionCreateDto.builder().planCode("PRO").build()))
                .isInstanceOf(BillingNotConfiguredException.class);
        verify(sessions, never()).save(any());
    }

    @Test
    void anOrganizationAlreadyLiveOnThePlanIsRefused() {
        razorpayWired();
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(pro));
        when(subscriptions.findActiveSubscription(ORG)).thenReturn(Optional.of(row(pro, SubscriptionStatus.ACTIVE, "sub_old")));

        assertThatThrownBy(() -> service.createSession(ORG, USER, CheckoutSessionCreateDto.builder().planCode("PRO").build()))
                .isInstanceOf(DuplicateResourceException.class);
        verify(gateway, never()).createSubscription(any());
    }

    @Test
    void anOrganizationLiveOnAnotherProviderPlanIsSentToChangePlan() {
        razorpayWired();
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        Plan starter = plan("STARTER", "999.00", "9990.00", 0);
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(pro));
        when(subscriptions.findActiveSubscription(ORG)).thenReturn(Optional.of(row(starter, SubscriptionStatus.ACTIVE, "sub_old")));

        assertThatThrownBy(() -> service.createSession(ORG, USER, CheckoutSessionCreateDto.builder().planCode("PRO").build()))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("change-plan");
        verify(gateway, never()).createSubscription(any());
    }

    @Test
    void aManualRowOnAnotherPlanDoesNotBlockAPaidCheckout() {
        razorpayWired();
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        Plan free = plan("FREE", "0.00", "0.00", 0);
        Subscription manual = row(free, SubscriptionStatus.ACTIVE, null);
        manual.setProvider(ProviderId.MANUAL);
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(pro));
        when(subscriptions.findActiveSubscription(ORG)).thenReturn(Optional.of(manual));
        when(subscriptions.findByProviderAndExternalSubscriptionId(ProviderId.RAZORPAY, "sub_1")).thenReturn(Optional.empty());
        when(sessions.findFirstByOrganization_IdAndPlanCodeAndBillingPeriodAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());

        CheckoutSessionDto dto = service.createSession(ORG, USER, CheckoutSessionCreateDto.builder().planCode("PRO").build());

        assertThat(dto.getProviderSubscriptionId()).isEqualTo("sub_1");
        verify(gateway).createSubscription(any());
    }

    @Test
    void aLiveGatewayWithoutAPublicKeyIsNotCheckoutReady() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.providerId()).thenReturn(ProviderId.RAZORPAY);
        when(gateway.publicKeyId()).thenReturn("");
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(plan("PRO", "9999.00", "99990.00", 0)));

        BillingProviderInfoDto info = service.providerInfo();
        assertThat(info.isEnabled()).isFalse();
        assertThat(info.getSupportedFlows()).isEmpty();
        assertThatThrownBy(() -> service.createSession(ORG, USER, CheckoutSessionCreateDto.builder().planCode("PRO").build()))
                .isInstanceOf(BillingNotConfiguredException.class);
        verify(gateway, never()).createSubscription(any());
    }

    @Test
    void aCycleAboveTheAfaCapNeedsTheBuyersAcceptanceBeforeThePortIsCalled() {
        razorpayWired();
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(pro));
        when(subscriptions.findActiveSubscription(ORG)).thenReturn(Optional.empty());
        when(sessions.findFirstByOrganization_IdAndPlanCodeAndBillingPeriodAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSession(ORG, USER,
                CheckoutSessionCreateDto.builder().planCode("PRO").billingPeriod(BillingPeriod.ANNUAL).build()))
                .isInstanceOf(MandatePolicyViolationException.class)
                .hasMessageContaining("above the RBI cap");
        verify(gateway, never()).createSubscription(any());

        CheckoutSessionDto dto = service.createSession(ORG, USER, CheckoutSessionCreateDto.builder()
                .planCode("PRO").billingPeriod(BillingPeriod.ANNUAL).acceptPerChargeAfa(true).build());
        assertThat(dto.getMandate().isPerChargeApproval()).isTrue();
        assertThat(dto.getMandate().getAmountCapPaise()).isEqualTo(9_999_000L);
    }

    @Test
    void anOrganizationWithNoNotificationChannelCannotOpenARecurringCheckout() {
        razorpayWired();
        organization.setOrganizationEmail(null);
        organization.setOrganizationPhone(" ");
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(plan("PRO", "9999.00", "99990.00", 0)));
        when(subscriptions.findActiveSubscription(ORG)).thenReturn(Optional.empty());
        when(sessions.findFirstByOrganization_IdAndPlanCodeAndBillingPeriodAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSession(ORG, USER, CheckoutSessionCreateDto.builder().planCode("PRO").build()))
                .isInstanceOf(MandatePolicyViolationException.class)
                .hasMessageContaining("pre-debit notification");
        verify(gateway, never()).createSubscription(any());
    }

    // -- verify ------------------------------------------------------------------------------

    private VerifyCheckoutDto result(String signature) {
        return VerifyCheckoutDto.builder().providerPaymentId("pay_1").providerSubscriptionId("sub_1").providerSignature(signature).build();
    }

    @Test
    void verifyRejectsABadSignatureAndProjectsNothing() {
        razorpayWired();
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        when(sessions.findFirstByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, "sub_1"))
                .thenReturn(Optional.of(openSession("sub_1", pro, BillingPeriod.MONTHLY)));
        when(gateway.verifyCheckoutSignature(any())).thenReturn(false);

        assertThatThrownBy(() -> service.verify(ORG, result("deadbeef"))).isInstanceOf(InvalidRequestException.class);
        verify(projection, never()).apply(anyLong(), any());
        verify(sessions, never()).save(any());
    }

    @Test
    void verifyActivatesExactlyOnceAndASecondCallReturnsTheRow() {
        razorpayWired();
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        CheckoutSession session = openSession("sub_1", pro, BillingPeriod.MONTHLY);
        when(sessions.findFirstByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, "sub_1")).thenReturn(Optional.of(session));
        when(gateway.verifyCheckoutSignature(any())).thenReturn(true);
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(pro));
        Subscription incomplete = row(pro, SubscriptionStatus.INCOMPLETE, "sub_1");
        when(subscriptions.findByProviderAndExternalSubscriptionId(ProviderId.RAZORPAY, "sub_1")).thenReturn(Optional.of(incomplete));
        when(projection.apply(eq(ORG), any())).thenAnswer(inv -> {
            incomplete.setStatus(SubscriptionStatus.ACTIVE);
            return "INCOMPLETE -> ACTIVE";
        });

        SubscriptionDto first = service.verify(ORG, result("good"));

        ArgumentCaptor<NormalizedBillingEvent> event = ArgumentCaptor.forClass(NormalizedBillingEvent.class);
        verify(projection, times(1)).apply(eq(ORG), event.capture());
        assertThat(event.getValue().providerSubscriptionId()).isEqualTo("sub_1");
        assertThat(event.getValue().planCode()).isEqualTo("PRO");
        assertThat(event.getValue().subscription().status()).isEqualTo(NormalizedSubscriptionStatus.AUTHENTICATED);
        assertThat(first.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(first.getProvider()).isEqualTo("RAZORPAY");
        assertThat(first.getProviderSubscriptionId()).isEqualTo("sub_1");
        assertThat(session.getStatus()).isEqualTo(CheckoutSessionStatus.VERIFIED);
        assertThat(session.getProviderPaymentId()).isEqualTo("pay_1");
        assertThat(session.getSubscriptionId()).isEqualTo(101L);
        verify(cache).evictOnWrite(ORG);

        SubscriptionDto second = service.verify(ORG, result("good"));

        verify(projection, times(1)).apply(anyLong(), any());
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void verifyDefersToAWebhookThatActivatedTheRowFirst() {
        razorpayWired();
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        CheckoutSession session = openSession("sub_1", pro, BillingPeriod.MONTHLY);
        when(sessions.findFirstByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, "sub_1")).thenReturn(Optional.of(session));
        when(gateway.verifyCheckoutSignature(any())).thenReturn(true);
        Subscription live = row(pro, SubscriptionStatus.ACTIVE, "sub_1");
        when(subscriptions.findByProviderAndExternalSubscriptionId(ProviderId.RAZORPAY, "sub_1")).thenReturn(Optional.of(live));

        SubscriptionDto dto = service.verify(ORG, result("good"));

        verify(projection, never()).apply(anyLong(), any());
        assertThat(dto.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(session.getStatus()).isEqualTo(CheckoutSessionStatus.VERIFIED);
    }

    @Test
    void verifyNeverReturnsAnotherOrganizationsSession() {
        razorpayWired();
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        when(sessions.findFirstByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, "sub_1"))
                .thenReturn(Optional.of(openSession("sub_1", pro, BillingPeriod.MONTHLY)));

        assertThatThrownBy(() -> service.verify(ORG + 1, result("good")))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        verify(gateway, never()).verifyCheckoutSignature(any());
    }

    @Test
    void aTrialPlanProjectsAsTrialing() {
        Plan trial = plan("TRIAL", "4999.00", "49990.00", 14);
        CheckoutSession session = openSession("sub_t", trial, BillingPeriod.MONTHLY);
        NormalizedBillingEvent event = CheckoutService.activation(ProviderId.RAZORPAY, session, trial);
        assertThat(event.subscription().status()).isEqualTo(NormalizedSubscriptionStatus.TRIAL);
        assertThat(event.subscription().currentPeriodEnd()).isAfter(Instant.now().plus(13, ChronoUnit.DAYS));
        assertThat(event.subscription().currentPeriodEnd()).isBefore(Instant.now().plus(15, ChronoUnit.DAYS));
    }

    // -- mandate -----------------------------------------------------------------------------

    @Test
    void mandateAcknowledgementEnforcesTheCapAndReturnsTheConstraints() {
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        when(plans.findByCodeWithFeatures("PRO")).thenReturn(Optional.of(pro));
        when(sessions.findFirstByOrganization_IdAndPlanCodeAndBillingPeriodAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledgeMandate(ORG,
                MandateAcknowledgeDto.builder().planCode("PRO").billingPeriod(BillingPeriod.ANNUAL).build()))
                .isInstanceOf(MandatePolicyViolationException.class);

        CheckoutMandateTermsDto annual = service.acknowledgeMandate(ORG, MandateAcknowledgeDto.builder()
                .planCode("PRO").billingPeriod(BillingPeriod.ANNUAL).acceptPerChargeAfa(true).method(MandateMethod.ENACH).build());
        assertThat(annual.isPerChargeApproval()).isTrue();
        assertThat(annual.getAmountCapPaise()).isEqualTo(9_999_000L);
        assertThat(annual.getMethod()).isEqualTo(MandateMethod.ENACH);
        assertThat(annual.getPreDebitNoticeHours()).isEqualTo(24);

        CheckoutMandateTermsDto monthly = service.acknowledgeMandate(ORG,
                MandateAcknowledgeDto.builder().planCode("PRO").billingPeriod(BillingPeriod.MONTHLY).build());
        assertThat(monthly.isPerChargeApproval()).isFalse();
        assertThat(monthly.getMethod()).isEqualTo(MandateMethod.UNKNOWN);
    }

    // -- dto ---------------------------------------------------------------------------------

    @Test
    void subscriptionDtoCarriesTheProviderFieldsTheCoreParses() {
        Plan pro = plan("PRO", "9999.00", "99990.00", 0);
        Subscription live = row(pro, SubscriptionStatus.PAST_DUE, "sub_1");
        Instant pastDue = Instant.now().minus(2, ChronoUnit.DAYS);
        live.setPastDueSince(pastDue);

        SubscriptionDto dto = BillingMapper.toSubscriptionDto(live);

        assertThat(dto.getProvider()).isEqualTo("RAZORPAY");
        assertThat(dto.getProviderSubscriptionId()).isEqualTo("sub_1");
        assertThat(dto.getNextChargeAt()).isEqualTo(live.getCurrentPeriodEnd());
        assertThat(dto.getPastDueSince()).isEqualTo(pastDue);
        assertThat(dto.getCancelAtPeriodEnd()).isFalse();

        Subscription manual = row(pro, SubscriptionStatus.ACTIVE, null);
        manual.setProvider(ProviderId.MANUAL);
        SubscriptionDto manualDto = BillingMapper.toSubscriptionDto(manual);
        assertThat(manualDto.getProvider()).isEqualTo("NONE");
        assertThat(manualDto.getProviderSubscriptionId()).isNull();
        assertThat(manualDto.getNextChargeAt()).isNull();

        live.setCancelAtPeriodEnd(true);
        assertThat(BillingMapper.toSubscriptionDto(live).getNextChargeAt()).isNull();
    }
}

package org.tornotron.echno_backend.billing.reconcile;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.billing.Feature;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.PlanFeature;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.entitlement.ManualTrial;
import org.tornotron.echno_backend.billing.entitlement.PastDueGracePolicy;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queries behind the manual trial end (ClickUp 14zdkkvzx5e), against a real database: an
 * ended manual trial no longer reads as the active subscription, the sweep finds exactly the
 * ended manual trials, and one pass moves the organization to the Free plan for good.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SubscriptionService.class, SubscriptionCache.class, PastDueGracePolicy.class,
        ManualTrialExpirySweep.class, TenantScopedJobRunner.class, TransactionalWorkRunner.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManualTrialEndIT extends AbstractIntegrationTest {

    private static final Long ENDED_ORG = 992_001L;
    private static final Long PROVIDER_ORG = 992_002L;
    private static final Long RUNNING_ORG = 992_003L;
    private static final String TRIAL_PLAN = "trial-it-plan";
    private static final String PREMIUM = "TRIAL_IT_PREMIUM";

    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionCache subscriptionCache;
    @Autowired private ManualTrialExpirySweep sweep;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    private boolean createdFreePlan;
    private Long endedTrialId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        subscriptionCache.evictAll();
        inCommittedTx(() -> {
            if (planRepository.findByCodeAndIsActiveTrue(ManualTrial.FALLBACK_PLAN_CODE).isEmpty()) {
                entityManager.persist(Plan.builder().code(ManualTrial.FALLBACK_PLAN_CODE).name("Free Plan").isActive(true).build());
                createdFreePlan = true;
            }
            Feature feature = Feature.builder().code(PREMIUM).name("Trial IT premium")
                    .featureType(FeatureType.BOOLEAN).isActive(true).build();
            entityManager.persist(feature);
            Plan plan = Plan.builder().code(TRIAL_PLAN).name(TRIAL_PLAN).trialDays(30).isActive(true).build();
            plan.addFeature(PlanFeature.builder().feature(feature).enabled(true).build());
            entityManager.persist(plan);

            Instant now = Instant.now();
            Subscription ended = trial(ENDED_ORG, plan, now.minus(1, ChronoUnit.HOURS));
            entityManager.persist(ended);
            endedTrialId = ended.getId();

            Subscription provider = trial(PROVIDER_ORG, plan, now.minus(1, ChronoUnit.HOURS));
            provider.setProvider(ProviderId.RAZORPAY);
            provider.setExternalSubscriptionId("sub_trial_it");
            entityManager.persist(provider);

            entityManager.persist(trial(RUNNING_ORG, plan, now.plus(1, ChronoUnit.DAYS)));
            entityManager.flush();
        });
    }

    /** A trial on a yearly period, as createSubscription writes one, ending at the given instant. */
    private static Subscription trial(Long organizationId, Plan plan, Instant trialEnd) {
        Instant start = trialEnd.minus(30, ChronoUnit.DAYS);
        return Subscription.builder()
                .organizationId(organizationId).plan(plan)
                .status(SubscriptionStatus.TRIALING)
                .currentPeriodStart(start).currentPeriodEnd(start.plus(365, ChronoUnit.DAYS))
                .trialStart(start).trialEnd(trialEnd)
                .build();
    }

    @AfterEach
    void cleanup() {
        subscriptionCache.evictAll();
        inCommittedTx(() -> {
            entityManager.createNativeQuery("DELETE FROM subscription WHERE organization_id IN ("
                    + ENDED_ORG + ", " + PROVIDER_ORG + ", " + RUNNING_ORG + ")").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM plan_feature WHERE plan_id IN "
                    + "(SELECT id FROM plan WHERE code = '" + TRIAL_PLAN + "')").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM plan WHERE code = '" + TRIAL_PLAN + "'").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM feature WHERE code = '" + PREMIUM + "'").executeUpdate();
            if (createdFreePlan) {
                entityManager.createNativeQuery("DELETE FROM plan WHERE code = '"
                        + ManualTrial.FALLBACK_PLAN_CODE + "'").executeUpdate();
            }
        });
        createdFreePlan = false;
        TenantContext.clear();
    }

    @Test
    void anEndedManualTrial_isNotTheActiveSubscription_butProviderAndRunningTrialsAre() {
        assertThat(inCommittedTx(() -> subscriptionRepository.findActiveSubscription(ENDED_ORG))).isEmpty();
        assertThat(inCommittedTx(() -> subscriptionRepository.findActiveSubscription(PROVIDER_ORG))).isPresent();
        assertThat(inCommittedTx(() -> subscriptionRepository.findActiveSubscription(RUNNING_ORG))).isPresent();
    }

    @Test
    void theSweepQuery_findsOnlyEndedManualTrials() {
        List<Long> ids = inCommittedTx(() -> subscriptionRepository
                .findEndedManualTrials(Instant.now(), PageRequest.of(0, 1000)).stream()
                .filter(s -> List.of(ENDED_ORG, PROVIDER_ORG, RUNNING_ORG).contains(s.getOrganizationId()))
                .map(Subscription::getId).toList());

        assertThat(ids).containsExactly(endedTrialId);
        assertThat(inCommittedTx(() -> subscriptionRepository.findEndedManualTrials(ENDED_ORG, Instant.now())))
                .extracting(Subscription::getId).containsExactly(endedTrialId);
    }

    @Test
    void beforeTheSweep_theGateRefusesThePremiumFeatureNamingTheEndedTrial() {
        FeatureAccessResultDto access = subscriptionService.checkFeatureAccess(ENDED_ORG, PREMIUM);

        assertThat(access.isAllowed()).isFalse();
        assertThat(access.getReason()).startsWith("Trial ended at ");
        assertThat(subscriptionService.checkFeatureAccess(RUNNING_ORG, PREMIUM).isAllowed()).isTrue();
    }

    @Test
    void onePass_movesTheOrganizationToFree_andASecondChangesNothing() {
        sweep.runPass(Instant.now());

        Subscription trial = inCommittedTx(() -> subscriptionRepository.findById(endedTrialId).orElseThrow());
        assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(trial.getCurrentPeriodEnd()).isEqualTo(trial.getTrialEnd());

        String activePlan = inCommittedTx(() -> subscriptionRepository.findActiveSubscription(ENDED_ORG)
                .orElseThrow().getPlan().getCode());
        assertThat(activePlan).isEqualTo(ManualTrial.FALLBACK_PLAN_CODE);
        assertThat(subscriptionService.checkFeatureAccess(ENDED_ORG, PREMIUM).getReason())
                .isEqualTo("Feature not included in current plan");

        sweep.runPass(Instant.now());

        assertThat(inCommittedTx(() -> subscriptionRepository.findByOrganizationIdOrderByCreatedAtDesc(ENDED_ORG)))
                .hasSize(2);
        assertThat(inCommittedTx(() -> subscriptionRepository.findActiveSubscription(PROVIDER_ORG)
                .orElseThrow().getStatus())).isEqualTo(SubscriptionStatus.TRIALING);
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

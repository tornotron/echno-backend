package org.tornotron.echno_backend.billing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.BillingMapper;
import org.tornotron.echno_backend.billing.dto.PlanCreateDto;
import org.tornotron.echno_backend.billing.dto.PlanDto;
import org.tornotron.echno_backend.billing.dto.PlanFeatureAssignDto;
import org.tornotron.echno_backend.billing.dto.PlanFeatureDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.snapshot.SubscriptionSnapshot;
import org.tornotron.echno_backend.billing.services.PlanService;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the transaction boundary the billing endpoints depend on. With
 * {@code spring.jpa.open-in-view} off, a controller that hands an entity to
 * {@code BillingMapper} maps against a closed persistence context, and the lazy
 * {@code Subscription.plan} and {@code Plan.planFeatures} reads throw
 * {@link LazyInitializationException}. The endpoints therefore take their DTOs from
 * {@link SubscriptionService} and {@link PlanService}, which map inside their own transactions.
 *
 * <p>Every test here runs without the ambient rolled-back transaction, so the services are
 * called exactly as an HTTP request calls them, with nothing open around them.
 *
 * <p>Both halves share this one context on purpose. The test JVM caps its heap and Spring keeps
 * every distinct test context it builds, so a second slice for the plan half would cost heap for
 * no coverage the plan tests could not get here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SubscriptionService.class, SubscriptionCache.class, PlanService.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingMappingIT extends AbstractIntegrationTest {

    private static final Long SUBSCRIBED_USER = 991_001L;
    private static final Long UNSUBSCRIBED_USER = 991_002L;
    private static final String CURRENT_PLAN = "mapping-it-plan-a";
    private static final String TARGET_PLAN = "mapping-it-plan-b";
    private static final String FEATURE_CODE = "MAPPING_IT_FEATURE";
    private static final String CREATED_PLAN = "mapping-it-plan-c";

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PlanService planService;

    @Autowired
    private SubscriptionCache subscriptionCache;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        subscriptionCache.evictAll();
        inCommittedTx(() -> {
            Feature feature = Feature.builder()
                    .code(FEATURE_CODE)
                    .name("Mapping IT feature")
                    .featureType(FeatureType.BOOLEAN)
                    .isActive(true)
                    .build();
            entityManager.persist(feature);

            Plan current = persistPlan(CURRENT_PLAN, feature);
            persistPlan(TARGET_PLAN, feature);

            Instant now = Instant.now();
            entityManager.persist(Subscription.builder()
                    .userId(SUBSCRIBED_USER)
                    .plan(current)
                    .status(SubscriptionStatus.ACTIVE)
                    .currentPeriodStart(now)
                    .currentPeriodEnd(now.plus(30, ChronoUnit.DAYS))
                    .build());

            entityManager.flush();
        });
    }

    @AfterEach
    void cleanup() {
        subscriptionCache.evictAll();
        inCommittedTx(() -> {
            executeUpdate("DELETE FROM usage_record WHERE user_id IN ("
                    + SUBSCRIBED_USER + ", " + UNSUBSCRIBED_USER + ")");
            executeUpdate("DELETE FROM subscription WHERE user_id IN ("
                    + SUBSCRIBED_USER + ", " + UNSUBSCRIBED_USER + ")");
            executeUpdate("DELETE FROM plan_feature WHERE plan_id IN "
                    + "(SELECT id FROM plan WHERE code LIKE 'mapping-it-plan-%')");
            executeUpdate("DELETE FROM plan WHERE code LIKE 'mapping-it-plan-%'");
            executeUpdate("DELETE FROM feature WHERE code = '" + FEATURE_CODE + "'");
        });
        TenantContext.clear();
    }

    /**
     * The regression this class exists for: mapping a subscription entity after its session
     * has closed, which is what the controller used to do, cannot work. Anything that moves
     * the mapping back out of a transaction fails here.
     */
    @Test
    void mappingASubscriptionEntityOutsideATransaction_throwsLazyInitialization() {
        List<Subscription> subscriptions =
                inCommittedTx(() -> subscriptionRepository.findByUserIdOrderByCreatedAtDesc(SUBSCRIBED_USER));

        assertThatThrownBy(() -> BillingMapper.toSubscriptionDtoList(subscriptions))
                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    void getActiveSubscription_returnsAFullyMappedDto() {
        SubscriptionDto dto = subscriptionService.getActiveSubscription(SUBSCRIBED_USER).orElseThrow();

        assertFullyMapped(dto, CURRENT_PLAN);
    }

    @Test
    void getSubscriptionHistory_returnsFullyMappedDtos() {
        List<SubscriptionDto> history = subscriptionService.getSubscriptionHistory(SUBSCRIBED_USER);

        assertThat(history).hasSize(1);
        assertFullyMapped(history.get(0), CURRENT_PLAN);
    }

    @Test
    void createSubscription_returnsAFullyMappedDto() {
        SubscriptionDto dto = subscriptionService.createSubscription(
                UNSUBSCRIBED_USER, TARGET_PLAN, BillingPeriod.MONTHLY);

        assertFullyMapped(dto, TARGET_PLAN);
    }

    @Test
    void changeSubscription_returnsAFullyMappedDto() {
        SubscriptionDto dto = subscriptionService.changeSubscription(SUBSCRIBED_USER, TARGET_PLAN);

        assertFullyMapped(dto, TARGET_PLAN);
    }

    /**
     * What the fetch joins on {@code findActiveSubscriptionByUserId} are worth now that the
     * cache holds a snapshot rather than the entity. The snapshot is copied inside the loading
     * transaction, so the lazy reads resolve there either way and weakening the query costs a
     * round trip per plan feature instead of a 500. This pins the query all the same: it is the
     * one read whose result the cache is built from, so its shape is worth knowing.
     */
    @Test
    void theQueryBehindTheCacheReturnsAGraphThatOutlivesItsSession() {
        Subscription subscription = inCommittedTx(() ->
                subscriptionRepository.findActiveSubscriptionByUserId(SUBSCRIBED_USER).orElseThrow());

        assertFullyMapped(BillingMapper.toSubscriptionDto(subscription), CURRENT_PLAN);
    }

    /**
     * The invariant that replaced it. What the cache holds is a complete copy: every field the
     * response needs, the plan and its features included, resolved before the loading
     * transaction ended. Nothing on it can throw {@link LazyInitializationException}, because
     * there is no entity left on it to throw one, so this maps it with nothing open around it.
     */
    @Test
    void whatTheCacheHoldsIsACompleteCopyWithNoPersistenceStateLeftOnIt() {
        subscriptionService.getActiveSubscription(SUBSCRIBED_USER).orElseThrow();

        SubscriptionSnapshot cached = subscriptionCache.get(SUBSCRIBED_USER);
        assertThat(cached).as("the read should have populated the cache").isNotNull();

        assertFullyMapped(BillingMapper.toSubscriptionDto(cached), CURRENT_PLAN);
    }

    /**
     * The cached value is shared by every concurrent reader of this user, so a write path must
     * not change it in place. changeSubscription used to resolve its subscription through the
     * cache and call setPlan on whatever came back, which published the new plan to every reader
     * before the transaction had committed, and left the cache holding it if the transaction then
     * rolled back. A snapshot has no setter to call, and this holds the pre-write value to say so.
     */
    @Test
    void changeSubscription_leavesTheCachedValueAlone() {
        subscriptionService.getActiveSubscription(SUBSCRIBED_USER).orElseThrow();
        SubscriptionSnapshot cached = subscriptionCache.get(SUBSCRIBED_USER);
        assertThat(cached.plan().code()).isEqualTo(CURRENT_PLAN);

        subscriptionService.changeSubscription(SUBSCRIBED_USER, TARGET_PLAN);

        assertThat(cached.plan().code())
                .as("the write must not have changed the shared cached value")
                .isEqualTo(CURRENT_PLAN);
        assertThat(subscriptionService.getActiveSubscription(SUBSCRIBED_USER).orElseThrow()
                .getPlan().getCode())
                .as("and the next read must see the new plan")
                .isEqualTo(TARGET_PLAN);
    }

    /**
     * The same hazard on the cancellation path, which used to set the status and the
     * cancellation timestamp straight onto the cached instance.
     */
    @Test
    void cancelSubscription_leavesTheCachedValueAlone() {
        subscriptionService.getActiveSubscription(SUBSCRIBED_USER).orElseThrow();
        SubscriptionSnapshot cached = subscriptionCache.get(SUBSCRIBED_USER);
        assertThat(cached.status()).isEqualTo(SubscriptionStatus.ACTIVE);

        subscriptionService.cancelSubscription(SUBSCRIBED_USER, true);

        assertThat(cached.status())
                .as("the write must not have changed the shared cached value")
                .isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(cached.canceledAt()).isNull();
    }

    /**
     * A snapshot carries a copy of the plan it was taken against, so a plan write leaves every
     * subscriber of that plan holding entitlements the plan no longer grants. Nothing about the
     * subscription row changes, so no per-user eviction fires and the stale copies would stand
     * for the rest of the five minute window. The plan writes therefore empty the cache.
     *
     * <p>This is a staleness the choice of cached value cannot fix. The entity cache had it too,
     * for the same reason: what it held was a detached plan graph read at some earlier moment.
     */
    @Test
    void removingAFeatureFromAPlan_invalidatesTheCachedSubscriptions() {
        subscriptionService.getActiveSubscription(SUBSCRIBED_USER).orElseThrow();
        assertThat(subscriptionCache.get(SUBSCRIBED_USER))
                .as("the read should have populated the cache").isNotNull();

        Long planId = inCommittedTx(() ->
                planRepository.findByCodeWithFeatures(CURRENT_PLAN).orElseThrow().getId());
        planService.removeFeatureFromPlan(planId, FEATURE_CODE);

        assertThat(subscriptionCache.get(SUBSCRIBED_USER))
                .as("a plan write must not leave subscribers holding the old plan")
                .isNull();
        assertThat(subscriptionService.getActiveSubscription(SUBSCRIBED_USER).orElseThrow()
                .getPlan().getFeatures())
                .as("and the next read must see the plan without the feature")
                .isEmpty();
    }

    /**
     * Evicting at the point of the write is not enough on its own: between that eviction and
     * the commit, a concurrent read still sees the old row and caches it for the rest of the
     * TTL, so a user who has just changed plan keeps the old entitlement for five minutes.
     *
     * <p>A race is not reproducible, so the concurrent read is stood in for by a
     * synchronization that repopulates the cache during the same commit, which puts the entry
     * back at precisely the point the real race would. Nothing may survive completion.
     */
    @Test
    void changeSubscription_evictsAgainOnceItsTransactionHasCompleted() {
        SubscriptionSnapshot concurrentlyCachedRow = inCommittedTx(() ->
                BillingMapper.toSubscriptionSnapshot(subscriptionRepository
                        .findActiveSubscriptionByUserId(SUBSCRIBED_USER).orElseThrow()));

        inCommittedTx(() -> {
            subscriptionService.changeSubscription(SUBSCRIBED_USER, TARGET_PLAN);

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    subscriptionCache.put(SUBSCRIBED_USER, concurrentlyCachedRow);
                }
            });
        });

        assertThat(subscriptionCache.get(SUBSCRIBED_USER))
                .as("a read that landed mid-write must not leave a stale entry behind")
                .isNull();
    }

    /**
     * The plan half of the same regression. {@code findById} does not fetch-join the features,
     * so a plan entity mapped after its session closes fails exactly as a subscription does.
     * PlanController used to map plan entities itself and survived only because every read on
     * PlanService happens to use a {@code ...WithFeatures} query.
     */
    @Test
    void mappingAPlanEntityOutsideATransaction_throwsLazyInitialization() {
        Long planId = inCommittedTx(() -> planRepository.findByCodeWithFeatures(CURRENT_PLAN)
                .orElseThrow()
                .getId());

        Plan plan = inCommittedTx(() -> planRepository.findById(planId).orElseThrow());

        assertThatThrownBy(() -> BillingMapper.toPlanDto(plan))
                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    void getPlanById_returnsAFullyMappedDto() {
        Long planId = inCommittedTx(() -> planRepository.findByCodeWithFeatures(CURRENT_PLAN)
                .orElseThrow()
                .getId());

        assertFullyMapped(planService.getPlanById(planId), CURRENT_PLAN);
    }

    @Test
    void getPlanByCode_returnsAFullyMappedDto() {
        assertFullyMapped(planService.getPlanByCode(CURRENT_PLAN), CURRENT_PLAN);
    }

    @Test
    void getAllPlans_returnsFullyMappedDtos() {
        List<PlanDto> plans = planService.getAllPlans();

        assertFullyMapped(planOf(plans, CURRENT_PLAN), CURRENT_PLAN);
    }

    @Test
    void getAllPublicPlans_returnsFullyMappedDtos() {
        List<PlanDto> plans = planService.getAllPublicPlans();

        assertFullyMapped(planOf(plans, CURRENT_PLAN), CURRENT_PLAN);
    }

    /**
     * A plan straight off the builder has no features yet, so this also pins the
     * {@code @Builder.Default} on {@code Plan.planFeatures}: without it the collection is null
     * here and the very next assignFeatureToPlan would throw.
     */
    @Test
    void createPlan_returnsAFullyMappedDtoWithNoFeatures() {
        PlanDto created = planService.createPlan(createDto());

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCode()).isEqualTo(CREATED_PLAN);
        assertThat(created.getFeatures()).isEmpty();
    }

    @Test
    void assignFeatureToPlan_returnsAFullyMappedDtoOnABuilderConstructedPlan() {
        PlanDto created = planService.createPlan(createDto());

        PlanFeatureAssignDto assignment = new PlanFeatureAssignDto();
        assignment.setFeatureCode(FEATURE_CODE);
        assignment.setEnabled(true);

        PlanDto assigned = planService.assignFeatureToPlan(created.getId(), assignment);

        assertFullyMapped(assigned, CREATED_PLAN);
    }

    @Test
    void removeFeatureFromPlan_returnsAMappedDtoWithoutTheFeature() {
        Long planId = inCommittedTx(() -> planRepository.findByCodeWithFeatures(CURRENT_PLAN)
                .orElseThrow()
                .getId());

        PlanDto updated = planService.removeFeatureFromPlan(planId, FEATURE_CODE);

        assertThat(updated.getCode()).isEqualTo(CURRENT_PLAN);
        assertThat(updated.getFeatures()).isEmpty();
    }

    private PlanCreateDto createDto() {
        PlanCreateDto dto = new PlanCreateDto();
        dto.setCode(CREATED_PLAN);
        dto.setName("Mapping IT created plan");
        dto.setMonthlyPrice(new BigDecimal("50.00"));
        dto.setAnnualPrice(new BigDecimal("500.00"));
        return dto;
    }

    private PlanDto planOf(List<PlanDto> plans, String code) {
        return plans.stream()
                .filter(plan -> code.equals(plan.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No plan with code " + code + " in " + plans));
    }

    private void assertFullyMapped(PlanDto dto, String expectedPlanCode) {
        assertThat(dto.getId()).isNotNull();
        assertThat(dto.getCode()).isEqualTo(expectedPlanCode);
        assertThat(dto.getFeatures())
                .extracting(PlanFeatureDto::getFeatureCode)
                .containsExactly(FEATURE_CODE);
    }

    private void assertFullyMapped(SubscriptionDto dto, String expectedPlanCode) {
        assertThat(dto.getId()).isNotNull();
        assertThat(dto.getPlan()).isNotNull();
        assertThat(dto.getPlan().getCode()).isEqualTo(expectedPlanCode);
        assertThat(dto.getPlan().getFeatures())
                .extracting(PlanFeatureDto::getFeatureCode)
                .containsExactly(FEATURE_CODE);
    }

    private Plan persistPlan(String code, Feature feature) {
        Plan plan = Plan.builder()
                .code(code)
                .name(code)
                .monthlyPrice(new BigDecimal("100.00"))
                .annualPrice(new BigDecimal("1000.00"))
                .isActive(true)
                .isPublic(true)
                .build();
        plan.addFeature(PlanFeature.builder().feature(feature).enabled(true).build());
        entityManager.persist(plan);
        return plan;
    }

    private void executeUpdate(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
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

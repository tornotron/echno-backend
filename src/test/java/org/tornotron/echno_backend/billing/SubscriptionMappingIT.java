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
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.BillingMapper;
import org.tornotron.echno_backend.billing.dto.PlanFeatureDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the transaction boundary the subscription endpoints depend on. With
 * {@code spring.jpa.open-in-view} off, a controller that hands a Subscription entity to
 * {@code BillingMapper} maps against a closed persistence context, and the lazy
 * {@code Subscription.plan} and {@code Plan.planFeatures} reads throw
 * {@link LazyInitializationException}. The endpoints therefore take their DTOs from
 * {@link SubscriptionService}, which maps inside its own transaction.
 *
 * <p>Every test here runs without the ambient rolled-back transaction, so the service is
 * called exactly as an HTTP request calls it, with nothing open around it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SubscriptionService.class, SubscriptionCache.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SubscriptionMappingIT extends AbstractIntegrationTest {

    private static final Long SUBSCRIBED_USER = 991_001L;
    private static final Long UNSUBSCRIBED_USER = 991_002L;
    private static final String CURRENT_PLAN = "mapping-it-plan-a";
    private static final String TARGET_PLAN = "mapping-it-plan-b";
    private static final String FEATURE_CODE = "MAPPING_IT_FEATURE";

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionService subscriptionService;

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
                .planFeatures(new HashSet<>())
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

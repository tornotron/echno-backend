package org.tornotron.echno_backend.billing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the collection defaults on the billing entities.
 *
 * <p>Lombok's builder ignores a field initializer unless the field carries
 * {@code @Builder.Default}, so {@code Plan.builder().build()} used to leave {@code planFeatures}
 * null. Nothing returned a 500 over it, because {@code BillingMapper.toPlanDto} null-checks the
 * collection, but {@code Plan.addFeature} threw a NullPointerException on any plan that came off
 * the builder rather than out of the database, and callers had to remember to pass an empty set
 * to work around it.
 *
 * <p>Hibernate instantiates entities through the no-args constructor, so that path is asserted
 * too: an initializer that only the builder honours would be a different bug of the same shape.
 * No Spring context here on purpose, this is plain object construction.
 */
class BillingEntityDefaultsTest {

    @Test
    void aBuiltPlanStartsWithAnEmptyFeatureSet() {
        Plan plan = Plan.builder().code("starter").name("Starter").build();

        assertThat(plan.getPlanFeatures()).isNotNull().isEmpty();
    }

    @Test
    void aBuiltPlanAcceptsAFeatureWithoutABuilderWorkaround() {
        Plan plan = Plan.builder().code("starter").name("Starter").build();
        PlanFeature planFeature = PlanFeature.builder()
                .feature(Feature.builder().code("report-export").name("PDF Report Export").build())
                .enabled(true)
                .build();

        plan.addFeature(planFeature);

        assertThat(plan.getPlanFeatures()).containsExactly(planFeature);
        assertThat(planFeature.getPlan()).isSameAs(plan);
    }

    @Test
    void aNewPlanStartsWithAnEmptyFeatureSet() {
        assertThat(new Plan().getPlanFeatures()).isNotNull().isEmpty();
    }

    @Test
    void aBuiltFeatureStartsWithAnEmptyPlanFeatureSet() {
        Feature feature = Feature.builder().code("report-export").name("PDF Report Export").build();

        assertThat(feature.getPlanFeatures()).isNotNull().isEmpty();
    }

    @Test
    void aBuiltSubscriptionStartsWithAnEmptyItemSet() {
        Subscription subscription = Subscription.builder().userId(1L).build();

        assertThat(subscription.getSubscriptionItems()).isNotNull().isEmpty();
    }

    @Test
    void aNewSubscriptionStartsWithAnEmptyItemSet() {
        assertThat(new Subscription().getSubscriptionItems()).isNotNull().isEmpty();
    }
}

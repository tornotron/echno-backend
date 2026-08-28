package org.tornotron.echno_backend.billing.snapshot;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What makes the snapshot safe to cache, asserted directly on the type.
 *
 * <p>Plain JUnit on purpose: none of this needs a Spring context or a database, and the test
 * JVM keeps every context it builds.
 */
class SubscriptionSnapshotTest {

    private static final Instant PERIOD_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TRIAL_END = Instant.parse("2026-08-15T00:00:00Z");

    /** The value types a snapshot may be built out of, beyond records and enums. */
    private static final Set<Class<?>> VALUE_TYPES = Set.of(
            Long.class, Integer.class, Boolean.class, String.class, Instant.class, BigDecimal.class);

    private static SubscriptionSnapshot snapshot(SubscriptionStatus status) {
        return new SubscriptionSnapshot(
                1L, 42L, status,
                PERIOD_START, PERIOD_END, PERIOD_START, TRIAL_END,
                false, null, PERIOD_START, null,
                new PlanSnapshot(
                        7L, "professional-monthly", "Professional", "For growing teams", 1,
                        new BigDecimal("4999.00"), new BigDecimal("49990.00"), "INR",
                        true, true, 14, 25, 2,
                        List.of(new PlanFeatureSnapshot(
                                3L, 9L, "report-export", "PDF Report Export",
                                FeatureType.QUOTA, true, 500L, QuotaPeriod.MONTHLY))));
    }

    /**
     * The reason the cache holds this rather than {@code SubscriptionDto}. A DTO carries
     * {@code expired} as a boolean settled when it was built, so a subscription that lapsed
     * inside the cache window would go on reporting itself unexpired for the rest of it. The
     * snapshot keeps the timestamp and answers for the instant it is asked about, so one
     * cached value gives both answers as the window it describes passes.
     */
    @Test
    void expiredIsAnsweredForTheInstantAsked() {
        SubscriptionSnapshot subscription = snapshot(SubscriptionStatus.ACTIVE);

        assertThat(subscription.isExpired(PERIOD_END.minus(1, ChronoUnit.HOURS))).isFalse();
        assertThat(subscription.isExpired(PERIOD_END.plus(1, ChronoUnit.HOURS))).isTrue();
    }

    /** The same for the trial window, the other flag derived from the clock. */
    @Test
    void inTrialIsAnsweredForTheInstantAsked() {
        SubscriptionSnapshot subscription = snapshot(SubscriptionStatus.TRIALING);

        assertThat(subscription.isInTrial(TRIAL_END.minus(1, ChronoUnit.HOURS))).isTrue();
        assertThat(subscription.isInTrial(TRIAL_END.plus(1, ChronoUnit.HOURS))).isFalse();
    }

    /**
     * And the flag that is not of that kind, which is worth stating because it sits beside them
     * on the DTO. {@code active} reads the status alone, so it is exactly as old as the snapshot
     * however it is computed. Eviction and expiry bound it; no choice of value type does.
     */
    @Test
    void activeReadsTheStatusAloneAndNotTheClock() {
        assertThat(snapshot(SubscriptionStatus.ACTIVE).isActive()).isTrue();
        assertThat(snapshot(SubscriptionStatus.TRIALING).isActive()).isTrue();
        assertThat(snapshot(SubscriptionStatus.CANCELED).isActive()).isFalse();

        SubscriptionSnapshot expired = snapshot(SubscriptionStatus.ACTIVE);
        assertThat(expired.isExpired(PERIOD_END.plus(1, ChronoUnit.HOURS))).isTrue();
        assertThat(expired.isActive())
                .as("a lapsed period does not by itself change the recorded status")
                .isTrue();
    }

    @Test
    void featureLookupFindsTheGrantAndItsFeatureId() {
        SubscriptionSnapshot subscription = snapshot(SubscriptionStatus.ACTIVE);

        assertThat(subscription.feature("report-export"))
                .get()
                .satisfies(grant -> {
                    assertThat(grant.featureId()).isEqualTo(9L);
                    assertThat(grant.hasQuota()).isTrue();
                    assertThat(grant.isFeatureEnabled()).isTrue();
                });
        assertThat(subscription.feature("something-else")).isEmpty();
        assertThat(subscription.feature(null)).isEmpty();
    }

    /**
     * A cached value is shared by every concurrent reader of that user, so nothing handed to it
     * or taken from it may be changeable in place. The old cache held the entity, whose feature
     * set was the live collection.
     */
    @Test
    void theFeatureListIsCopiedInAndHandedOutUnmodifiable() {
        List<PlanFeatureSnapshot> features = new ArrayList<>(List.of(new PlanFeatureSnapshot(
                3L, 9L, "report-export", "PDF Report Export",
                FeatureType.QUOTA, true, 500L, QuotaPeriod.MONTHLY)));
        PlanSnapshot plan = new PlanSnapshot(
                7L, "code", "name", null, 1, null, null, "INR",
                true, true, 0, null, 0, features);

        features.clear();

        assertThat(plan.features()).hasSize(1);
        assertThatThrownBy(() -> plan.features().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The hazard the entity cache carried, closed structurally rather than by convention. A
     * detached entity in a cache throws {@code LazyInitializationException} on any part of it
     * that was left uninitialized, in a later transaction that cannot reattach it, and what kept
     * that from happening was a fetch-join query that nothing obliged to stay that way. Nothing
     * reachable from a snapshot is an entity, so there is nothing left to initialize.
     */
    @Test
    void nothingReachableFromASnapshotIsAnEntity() {
        Set<Class<?>> visited = new HashSet<>();
        assertNoEntitiesReachableFrom(SubscriptionSnapshot.class, visited);

        assertThat(visited).contains(PlanSnapshot.class, PlanFeatureSnapshot.class);
    }

    private static void assertNoEntitiesReachableFrom(Class<?> type, Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return;
        }

        assertThat(type.isAnnotationPresent(Entity.class))
                .as("%s is a persistent entity and must not be reachable from a cached snapshot",
                        type.getName())
                .isFalse();

        if (type.isEnum() || VALUE_TYPES.contains(type)) {
            return;
        }

        assertThat(type.isRecord())
                .as("%s is neither a record, an enum nor a known value type, so what it drags "
                        + "into the cache is not obvious", type.getName())
                .isTrue();

        for (RecordComponent component : type.getRecordComponents()) {
            for (Class<?> reachable : reachableTypes(component.getGenericType())) {
                assertNoEntitiesReachableFrom(reachable, visited);
            }
        }
    }

    private static List<Class<?>> reachableTypes(Type type) {
        if (type instanceof Class<?> raw) {
            return List.of(raw);
        }
        if (type instanceof ParameterizedType parameterized) {
            List<Class<?>> types = new ArrayList<>();
            for (Type argument : parameterized.getActualTypeArguments()) {
                types.addAll(reachableTypes(argument));
            }
            return types;
        }
        throw new AssertionError("Unhandled component type in a cached snapshot: " + type);
    }
}

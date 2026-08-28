package org.tornotron.echno_backend.billing.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;

import java.time.Instant;
import java.util.Optional;

/**
 * An immutable copy of a user's active subscription and the plan it entitles them to. This is
 * what {@code SubscriptionCache} holds.
 *
 * <p>The rule this type exists to enforce is that a cache stores facts and never verdicts.
 * Two of the three entitlement flags on {@code SubscriptionDto} are answers to the question
 * "as of when": {@code expired} compares {@link #currentPeriodEnd()} against the current
 * instant and {@code inTrial} compares {@link #trialEnd()}. Cached as computed booleans they
 * would be pinned for the life of the entry, and a subscription that lapsed inside that window
 * would go on reporting itself unexpired for the rest of it. So the snapshot keeps the
 * timestamps and answers the question when it is asked, through {@link #isExpired(Instant)}
 * and {@link #isInTrial(Instant)}, which take the instant to judge against rather than reading
 * the clock themselves.
 *
 * <p>{@link #isActive()} is deliberately not of that kind. It reads only {@link #status()}, so
 * it is as old as the snapshot however it is computed, and no representation can change that.
 * What bounds it is invalidation: the cache entry expires on its own timer, and every write
 * that changes a subscription's status evicts the user's entry. Nothing outside
 * {@code SubscriptionService} changes a status today, so that bound holds. A shorter bound is
 * a shorter cache lifetime, not a different value type.
 *
 * @param id Numeric id of the subscription.
 * @param userId Id of the subscribing user.
 * @param status Lifecycle status the subscription had when the snapshot was taken.
 * @param currentPeriodStart Start of the current billing period.
 * @param currentPeriodEnd End of the current billing period.
 * @param trialStart Start of the trial period, null if the subscription began without one.
 * @param trialEnd End of the trial period, null if the subscription began without one.
 * @param cancelAtPeriodEnd Whether the subscription is set to end at the current period's end.
 * @param canceledAt When the subscription was canceled, null if it has not been.
 * @param createdAt When the subscription was first created.
 * @param cancellationReason Reason given for cancellation, null if none was given.
 * @param plan The plan the subscription is on, with the features it grants.
 */
public record SubscriptionSnapshot(
        Long id,
        Long userId,
        SubscriptionStatus status,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        Instant trialStart,
        Instant trialEnd,
        Boolean cancelAtPeriodEnd,
        Instant canceledAt,
        Instant createdAt,
        String cancellationReason,
        PlanSnapshot plan) {

    /**
     * Whether the subscription was in a state that grants access when the snapshot was taken.
     *
     * <p>Derived from {@link #status()} rather than stored, and kept out of the serialized form
     * for that reason.
     *
     * @return {@code true} for an active or trialing subscription.
     */
    @JsonIgnore
    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIALING;
    }

    /**
     * Whether the subscription is inside its trial window at a given instant.
     *
     * @param at The instant to judge against, normally now.
     * @return {@code true} if the subscription is trialing and the trial has not ended by then.
     */
    public boolean isInTrial(Instant at) {
        return status == SubscriptionStatus.TRIALING
                && trialEnd != null
                && at.isBefore(trialEnd);
    }

    /**
     * Whether the current billing period has ended by a given instant.
     *
     * @param at The instant to judge against, normally now.
     * @return {@code true} if the period end is known and lies before then.
     */
    public boolean isExpired(Instant at) {
        return currentPeriodEnd != null && at.isAfter(currentPeriodEnd);
    }

    /**
     * Finds the subscribed plan's grant of one feature.
     *
     * @param featureCode The code of the feature to look for.
     * @return The plan's grant of that feature, or empty if the plan does not grant it.
     */
    public Optional<PlanFeatureSnapshot> feature(String featureCode) {
        return plan == null ? Optional.empty() : plan.feature(featureCode);
    }
}

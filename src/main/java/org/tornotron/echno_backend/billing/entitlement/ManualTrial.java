package org.tornotron.echno_backend.billing.entitlement;

import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.snapshot.SubscriptionSnapshot;

import java.time.Instant;

/**
 * When a trial that no payment provider backs stops granting its plan.
 *
 * <p>A trial granted by hand, or taken on a plan with no price, is a MANUAL row in TRIALING.
 * Nothing charges it at the end of the trial, so nothing would ever move it out of TRIALING,
 * and the gate used to read it as live until its billing period ended: a 30-day trial taken on
 * a yearly period kept every feature for a year. The product decision (ClickUp 14zdkkvzx5e) is
 * that such a trial ends on its trial end date and the organization drops to the
 * {@link #FALLBACK_PLAN_CODE} plan. The gate stops honouring the trial at {@code trialEnd}
 * through {@link PastDueGracePolicy#hasLapsed}, and {@code ManualTrialExpirySweep} writes the
 * move to the Free plan shortly after.
 *
 * <p>A provider-backed trial is the provider's to end: it charges at the trial end and the
 * webhook moves the row. Nothing here applies to one.
 *
 * <p>All instants are compared as instants, so the trial ends at the same moment whatever the
 * zone of the container or the viewer. A trial of N days ends N times 24 hours after it began.
 */
public final class ManualTrial {

    /** The plan an organization drops to when its manual trial ends. */
    public static final String FALLBACK_PLAN_CODE = "FREE";

    private ManualTrial() {
    }

    /** Whether no payment provider backs the row: the same reading the direct write paths use. */
    public static boolean isManual(ProviderId provider, String externalSubscriptionId) {
        return provider == null || provider == ProviderId.MANUAL || externalSubscriptionId == null;
    }

    /**
     * Whether a manual trial has ended by the given instant. The last entitled instant is the
     * one just before {@code trialEnd}.
     */
    public static boolean hasEnded(SubscriptionSnapshot subscription, Instant at) {
        return hasEnded(subscription.status(), subscription.trialEnd(), subscription.provider(),
                subscription.externalSubscriptionId(), at);
    }

    /** {@link #hasEnded(SubscriptionSnapshot, Instant)} for a managed row. */
    public static boolean hasEnded(Subscription subscription, Instant at) {
        return hasEnded(subscription.getStatus(), subscription.getTrialEnd(), subscription.getProvider(),
                subscription.getExternalSubscriptionId(), at);
    }

    private static boolean hasEnded(SubscriptionStatus status, Instant trialEnd, ProviderId provider,
                                    String externalSubscriptionId, Instant at) {
        return status == SubscriptionStatus.TRIALING
                && trialEnd != null
                && !at.isBefore(trialEnd)
                && isManual(provider, externalSubscriptionId);
    }
}

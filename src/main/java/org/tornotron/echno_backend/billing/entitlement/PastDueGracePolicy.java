package org.tornotron.echno_backend.billing.entitlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.snapshot.SubscriptionSnapshot;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * How long a PAST_DUE subscription keeps its entitlement. A failed renewal moves the row to
 * PAST_DUE while the provider retries the charge; cutting access at that instant would lock a
 * paying customer out over a declined card. So the gate keeps the organization entitled for
 * {@code echno.billing.past-due-grace-days} (default 7) from the moment it went past due, and
 * refuses from then on until a successful charge moves the row back to ACTIVE.
 *
 * <p>The window never ends before the paid period does: an organization that has paid up to
 * a date keeps access until that date whatever the grace setting says.
 *
 * <p>The judgement takes the instant to judge against, so a cached snapshot can be re-checked
 * on every read and evicted the moment its window lapses, the same way period expiry is.
 */
@Component
public class PastDueGracePolicy {

    private final int graceDays;

    public PastDueGracePolicy(@Value("${echno.billing.past-due-grace-days:7}") int graceDays) {
        this.graceDays = Math.max(0, graceDays);
    }

    public int graceDays() {
        return graceDays;
    }

    /**
     * When a past-due subscription's entitlement ends: the later of the paid period's end and
     * the grace window counted from when the row went past due (or from the period end, for a
     * row that carries no such timestamp).
     *
     * @param subscription A snapshot in PAST_DUE.
     * @return The instant from which the organization is no longer entitled.
     */
    public Instant entitledUntil(SubscriptionSnapshot subscription) {
        Instant periodEnd = subscription.currentPeriodEnd();
        Instant since = subscription.pastDueSince() != null ? subscription.pastDueSince() : periodEnd;
        Instant graceEnd = since.plus(graceDays, ChronoUnit.DAYS);
        return periodEnd != null && periodEnd.isAfter(graceEnd) ? periodEnd : graceEnd;
    }

    /**
     * Whether a snapshot no longer grants access at a given instant: a past-due subscription
     * whose window has ended, or any other whose period has.
     *
     * @param subscription The snapshot to judge.
     * @param at The instant to judge against, normally now.
     * @return {@code true} if the organization is not entitled at that instant.
     */
    public boolean hasLapsed(SubscriptionSnapshot subscription, Instant at) {
        if (subscription.status() == SubscriptionStatus.PAST_DUE) {
            return !at.isBefore(entitledUntil(subscription));
        }
        return subscription.isExpired(at);
    }
}

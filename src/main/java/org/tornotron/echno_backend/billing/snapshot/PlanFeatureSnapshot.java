package org.tornotron.echno_backend.billing.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;

/**
 * One feature as a plan grants it, copied out of the {@code PlanFeature} and {@code Feature}
 * rows behind it.
 *
 * <p>Carries {@link #featureId()}, which no response DTO does. Quota usage is recorded and
 * summed against {@code UsageRecord.featureId}, a plain column rather than a relation, so a
 * quota check needs that id and would otherwise have to go back to the database for it. It is
 * on the snapshot because the snapshot is internal; putting it on {@code PlanFeatureDto} would
 * have changed a public response contract to serve a caching decision.
 *
 * @param id Id of the plan-feature assignment row.
 * @param featureId Id of the feature itself, the key usage records are written against.
 * @param featureCode Code callers ask for, such as {@code report-export}.
 * @param featureName Display name of the feature.
 * @param featureType How the feature is measured.
 * @param enabled Whether the plan enables the feature, null when the row left it unset.
 * @param quotaLimit Usage allowed per quota period, null for an unmetered feature.
 * @param quotaPeriod Period over which the quota resets, null for an unmetered feature.
 */
public record PlanFeatureSnapshot(
        Long id,
        Long featureId,
        String featureCode,
        String featureName,
        FeatureType featureType,
        Boolean enabled,
        Long quotaLimit,
        QuotaPeriod quotaPeriod) {

    /**
     * Derived from {@link #enabled()} rather than stored, and kept out of the serialized form
     * for that reason: a shared cache holds the state a snapshot was taken with, never an
     * answer computed from it.
     *
     * @return {@code true} if the plan enables this feature.
     */
    @JsonIgnore
    public boolean isFeatureEnabled() {
        return enabled != null && enabled;
    }

    /**
     * @return {@code true} if this feature is metered against a quota on this plan.
     */
    public boolean hasQuota() {
        return quotaLimit != null && quotaLimit > 0;
    }
}

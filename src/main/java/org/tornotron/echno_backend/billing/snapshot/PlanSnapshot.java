package org.tornotron.echno_backend.billing.snapshot;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * An immutable copy of a plan and the features it grants, taken while the persistence context
 * that loaded it is still open.
 *
 * <p>Holds no entity and no lazy collection, so it can be read, mapped and cached long after
 * that context has closed.
 *
 * @param id Numeric id of the plan.
 * @param code Unique code identifying the plan.
 * @param name Display name of the plan.
 * @param description Longer description shown on the pricing page.
 * @param version Optimistic locking version of the plan record.
 * @param monthlyPrice Price charged per month, in the plan currency.
 * @param annualPrice Price charged per year, in the plan currency.
 * @param currency ISO 4217 currency code for the plan prices.
 * @param isActive Whether the plan currently accepts new subscriptions.
 * @param isPublic Whether the plan is shown on the public pricing page.
 * @param trialDays Number of trial days granted on first subscription.
 * @param maxUsers Maximum number of users allowed under this plan, null for unlimited.
 * @param sortOrder Display order relative to other plans, ascending.
 * @param features Features this plan grants, empty when it grants none.
 */
public record PlanSnapshot(
        Long id,
        String code,
        String name,
        String description,
        Integer version,
        BigDecimal monthlyPrice,
        BigDecimal annualPrice,
        String currency,
        Boolean isActive,
        Boolean isPublic,
        Integer trialDays,
        Integer maxUsers,
        Integer sortOrder,
        List<PlanFeatureSnapshot> features) {

    /**
     * Copies the feature list defensively so a caller cannot change what a cached snapshot
     * grants. {@code null} is kept as {@code null}, which is what a plan mapped without its
     * features looks like.
     */
    public PlanSnapshot {
        features = features == null ? null : List.copyOf(features);
    }

    /**
     * Finds the plan's grant of one feature.
     *
     * @param featureCode The code of the feature to look for.
     * @return The plan's grant of that feature, or empty if the plan does not grant it.
     */
    public Optional<PlanFeatureSnapshot> feature(String featureCode) {
        if (features == null || featureCode == null) {
            return Optional.empty();
        }
        return features.stream()
                .filter(feature -> featureCode.equals(feature.featureCode()))
                .findFirst();
    }
}

package org.tornotron.echno_backend.billing.entitlement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;

/**
 * The one place that decides what a refused entitlement means, so the aspect and the module
 * resolver cannot drift apart on it.
 *
 * <p>Nothing in the product creates a subscription yet: no payment integration exists and no
 * changelog seeds a {@code subscription} row, so a gate that refuses on "no subscription" would
 * refuse every tenant on the day it lands. {@code echno.entitlement.mode} therefore defaults to
 * {@link EntitlementMode#ADVISORY}: the check still runs in full, and a refusal is logged at
 * WARN with the organization, the feature and the reason, but the call goes ahead. Flipping an
 * environment to {@link EntitlementMode#ENFORCE} turns the same log lines into 402s without any
 * other change, which is the point of running advisory first: the log is the list of tenants
 * who would be cut off.
 */
@Component
@Slf4j
public class EntitlementPolicy {

    private final EntitlementMode mode;

    public EntitlementPolicy(@Value("${echno.entitlement.mode:advisory}") String mode) {
        this.mode = EntitlementMode.valueOf(mode.trim().toUpperCase());
    }

    public EntitlementMode mode() {
        return mode;
    }

    public boolean isEnforcing() {
        return mode == EntitlementMode.ENFORCE;
    }

    /**
     * Whether a call may go ahead given the outcome of its feature check.
     *
     * @param organizationId The organization the check was made for; null when none was in context.
     * @param featureCode The feature that was checked.
     * @param access The outcome of the check.
     * @return {@code true} if the call proceeds: always when the check allowed it, and in advisory
     *         mode also when it did not.
     */
    public boolean permits(Long organizationId, String featureCode, FeatureAccessResultDto access) {
        if (access.isAllowed()) {
            return true;
        }
        if (isEnforcing()) {
            return false;
        }
        log.warn("Entitlement advisory: organization {} would be refused feature {} ({}); allowing because echno.entitlement.mode=advisory",
                organizationId, featureCode, access.getReason());
        return true;
    }
}

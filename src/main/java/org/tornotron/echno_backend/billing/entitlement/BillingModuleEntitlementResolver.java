package org.tornotron.echno_backend.billing.entitlement;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.module.ModuleEntitlementResolver;

/**
 * The billing-backed answer to "is this organization entitled to this module": the module's
 * feature key is a {@code Feature} row, and the organization has it when its active plan grants
 * it. Runs through {@link EntitlementPolicy}, so in advisory mode a missing grant is logged and
 * reads as entitled.
 *
 * <p>Primary so that it wins over the permissive default the module registry ships for
 * environments without billing.
 */
@Component
@Primary
@RequiredArgsConstructor
public class BillingModuleEntitlementResolver implements ModuleEntitlementResolver {

    private final SubscriptionService subscriptionService;
    private final EntitlementPolicy policy;

    @Override
    public boolean isEntitled(Long organizationId, String featureKey) {
        if (organizationId == null) {
            return policy.permits(null, featureKey, FeatureAccessResultDto.noOrganization());
        }
        return policy.permits(organizationId, featureKey,
                subscriptionService.checkFeatureAccess(organizationId, featureKey));
    }
}

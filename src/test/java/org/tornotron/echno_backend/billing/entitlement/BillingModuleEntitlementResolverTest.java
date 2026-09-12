package org.tornotron.echno_backend.billing.entitlement;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.services.SubscriptionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The module registry's question, answered from billing, under each mode.
 */
class BillingModuleEntitlementResolverTest {

    private static final Long ORG = 21L;
    private static final String MODULE = "MODULE_INSPECTIONS";

    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);

    private BillingModuleEntitlementResolver resolver(String mode) {
        return new BillingModuleEntitlementResolver(subscriptionService, new EntitlementPolicy(mode));
    }

    @Test
    void aPlanWithoutTheFeatureIsNotEntitledInEnforceMode() {
        when(subscriptionService.checkFeatureAccess(ORG, MODULE)).thenReturn(FeatureAccessResultDto.featureNotInPlan());

        assertThat(resolver("enforce").isEntitled(ORG, MODULE)).isFalse();
    }

    @Test
    void aPlanWithoutTheFeatureIsEntitledInAdvisoryMode() {
        when(subscriptionService.checkFeatureAccess(ORG, MODULE)).thenReturn(FeatureAccessResultDto.featureNotInPlan());

        assertThat(resolver("advisory").isEntitled(ORG, MODULE)).isTrue();
    }

    @Test
    void aPlanWithTheFeatureIsEntitledInEitherMode() {
        when(subscriptionService.checkFeatureAccess(ORG, MODULE)).thenReturn(FeatureAccessResultDto.allowed());

        assertThat(resolver("enforce").isEntitled(ORG, MODULE)).isTrue();
        assertThat(resolver("advisory").isEntitled(ORG, MODULE)).isTrue();
    }

    @Test
    void noOrganizationIsRefusedInEnforceModeWithoutAskingBilling() {
        assertThat(resolver("enforce").isEntitled(null, MODULE)).isFalse();
        assertThat(resolver("advisory").isEntitled(null, MODULE)).isTrue();
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void theModeIsReadCaseInsensitivelyAndRejectsAnythingElse() {
        assertThat(new EntitlementPolicy("Enforce").mode()).isEqualTo(EntitlementMode.ENFORCE);
        assertThat(new EntitlementPolicy(" advisory ").isEnforcing()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new EntitlementPolicy("maybe"));
    }
}

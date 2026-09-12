package org.tornotron.echno_backend.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.entitlement.EntitlementPolicy;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.exception.SubscriptionAccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Gates a {@link RequireSubscription} method on the current organization's plan.
 *
 * <p>The annotation targets both methods and types, and the two advices below cover the two
 * placements. A method-level annotation wins: the class-level advice steps aside for any method
 * that carries its own, so one call is evaluated exactly once, against the more specific of the
 * two feature codes.
 *
 * <p>Whether a refusal becomes a 402 or a log line is {@link EntitlementPolicy}'s decision, not
 * this class's; see there for why the default is to log.
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SubscriptionAspect {

    private final SubscriptionService subscriptionService;
    private final UserContextService userContextService;
    private final EntitlementPolicy entitlementPolicy;

    @Around("@annotation(requireSubscription)")
    public Object checkMethodSubscription(
            ProceedingJoinPoint joinPoint,
            RequireSubscription requireSubscription
    ) throws Throwable {
        return gate(joinPoint, requireSubscription);
    }

    @Around("@within(requireSubscription) && !@annotation(org.tornotron.echno_backend.common.customAnnotation.RequireSubscription)")
    public Object checkClassSubscription(
            ProceedingJoinPoint joinPoint,
            RequireSubscription requireSubscription
    ) throws Throwable {
        return gate(joinPoint, requireSubscription);
    }

    private Object gate(ProceedingJoinPoint joinPoint, RequireSubscription requireSubscription) throws Throwable {
        Long organizationId = TenantContext.getCurrentOrgId();
        String featureCode = requireSubscription.feature();

        FeatureAccessResultDto accessResultDto = organizationId == null
                ? FeatureAccessResultDto.noOrganization()
                : subscriptionService.checkFeatureAccess(organizationId, featureCode);

        if(!entitlementPolicy.permits(organizationId, featureCode, accessResultDto)) {

            // The feature code is passed through so the refusal names the entitlement that was
            // missing. Without it the response carried no feature at all and the log line read
            // "Feature: null", leaving nobody able to tell which entitlement to look at.
            throw new SubscriptionAccessDeniedException(
                    requireSubscription.errorMessage().isEmpty()
                    ? (accessResultDto.getMessage() != null ? accessResultDto.getMessage() : accessResultDto.getReason())
                            : requireSubscription.errorMessage(),
                    featureCode,
                    accessResultDto
            );
        }

        Object result = joinPoint.proceed();

        if(requireSubscription.recordUsage() && organizationId != null) {
            try {
                subscriptionService.recordUsage(
                        organizationId,
                        userContextService.getCurrentUserId(),
                        featureCode,
                        requireSubscription.usageAmount()
                );
            } catch (Exception e) {
                log.error("Failed to record usage for organization {} and feature {}",
                        organizationId, featureCode, e);
            }
        }
        return result;
    }
}

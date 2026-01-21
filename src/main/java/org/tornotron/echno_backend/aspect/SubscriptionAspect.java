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
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.exception.SubscriptionAccessDeniedException;
import org.tornotron.echno_backend.user.UserContextService;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SubscriptionAspect {

    private final SubscriptionService subscriptionService;
    private final UserContextService userContextService;

    @Around("@annotation(requireSubscription)")
    public Object checkSubscription(
            ProceedingJoinPoint joinPoint,
            RequireSubscription requireSubscription
    ) throws Throwable {
        Long userId = userContextService.getCurrentUserIdOrThrow();

        String featureCode = requireSubscription.feature();

        FeatureAccessResultDto accessResultDto = subscriptionService
                .checkFeatureAccess(userId, featureCode);

        if(!accessResultDto.isAllowed()) {
            log.warn("Access denied for user {} to feature {}: {}",
                    userId, featureCode, accessResultDto.getReason());

            throw new SubscriptionAccessDeniedException(
                    requireSubscription.errorMessage().isEmpty()
                    ? accessResultDto.getReason()
                            : requireSubscription.errorMessage(),
                    accessResultDto
            );
        }

        Object result = joinPoint.proceed();

        if(requireSubscription.recordUsage()) {
            try {
                subscriptionService.recordUsage(
                        userId,
                        featureCode,
                        requireSubscription.usageAmount()
                );
            } catch (Exception e) {
                log.error("Failed to record usage for user {} and feature {}",
                        userId, featureCode, e);
            }
        }
        return result;
    }
}

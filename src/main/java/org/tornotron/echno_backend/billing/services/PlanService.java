package org.tornotron.echno_backend.billing.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.Feature;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.PlanFeature;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.BillingMapper;
import org.tornotron.echno_backend.billing.dto.PlanCreateDto;
import org.tornotron.echno_backend.billing.dto.PlanDto;
import org.tornotron.echno_backend.billing.dto.PlanFeatureAssignDto;
import org.tornotron.echno_backend.billing.repositories.FeatureRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import jakarta.validation.ValidationException;
import org.tornotron.echno_backend.common.exception.PlanNotFoundException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;

import java.util.List;

/**
 * Plan administration: reading plans with their assigned features, and creating, updating and
 * deactivating them.
 *
 * <p>Every write here empties the subscription cache. A cached subscription carries a copy of
 * the plan it was taken against, features and quotas included, so changing a plan changes what
 * every subscriber of it is entitled to while their cached copies still say otherwise. The
 * entries are not addressable by user without a query, and plan writes are rare administrative
 * operations, so the whole cache goes.
 *
 * <p>Entities never leave this class. Every method a caller can reach returns a DTO built while
 * the persistence context is still open, because {@code spring.jpa.open-in-view} is off and
 * {@link Plan#getPlanFeatures()} is a lazy collection: mapping a plan anywhere outside a
 * transaction throws {@code LazyInitializationException}. The reads here happen to use fetch-join
 * queries, which is what kept the old controller-side mapping working, but that is a property of
 * the queries rather than of the call site, and it is one query change away from the failure that
 * took down the subscription endpoints in #472.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlanService {

    private final PlanRepository planRepository;
    private final FeatureRepository featureRepository;
    private final SubscriptionCache subscriptionCache;

    /**
     * Returns the plans that are both public and active, ordered for display.
     *
     * @return The public plans, each with its assigned features.
     */
    @Transactional(readOnly = true)
    public List<PlanDto> getAllPublicPlans() {
        return BillingMapper.toPlanDtoList(planRepository.findPublicPlansWithFeatures());
    }

    /**
     * Returns every plan, including the private and inactive ones.
     *
     * @return All plans, each with its assigned features.
     */
    @Transactional(readOnly = true)
    public List<PlanDto> getAllPlans() {
        return BillingMapper.toPlanDtoList(planRepository.findAllWithFeatures());
    }

    /**
     * Returns a single plan by its numeric id.
     *
     * @param id The id of the plan to read.
     * @return The plan with its assigned features.
     * @throws PlanNotFoundException if no plan has that id.
     */
    @Transactional(readOnly = true)
    public PlanDto getPlanById(Long id) {
        return BillingMapper.toPlanDto(loadPlanById(id));
    }

    /**
     * Returns a single active plan by its unique code.
     *
     * @param code The code of the plan to read, such as {@code professional-monthly}.
     * @return The plan with its assigned features.
     * @throws PlanNotFoundException if no active plan has that code.
     */
    @Transactional(readOnly = true)
    public PlanDto getPlanByCode(String code) {
        return BillingMapper.toPlanDto(planRepository.findByCodeWithFeatures(code)
                .orElseThrow(() -> new PlanNotFoundException("Plan with code '" + code + "' was not found")));
    }

    /**
     * Loads a plan entity with its features initialized, for the methods here that go on to
     * change it. Private on purpose: an entity handed out of this class would be mapped against
     * a closed persistence context.
     */
    private Plan loadPlanById(Long id) {
        return planRepository.findByIdWithFeatures(id)
                .orElseThrow(() -> new PlanNotFoundException("Plan with ID " + id + " was not found"));
    }

    @Transactional
    public PlanDto createPlan(PlanCreateDto dto) {
        Plan plan = Plan.builder()
                .code(dto.getCode())
                .name(dto.getName())
                .description(dto.getDescription())
                .monthlyPrice(dto.getMonthlyPrice())
                .annualPrice(dto.getAnnualPrice())
                .currency(dto.getCurrency() != null ? dto.getCurrency() : "INR")
                .isPublic(dto.getIsPublic() != null ? dto.getIsPublic() : true)
                .trialDays(dto.getTrialDays() != null ? dto.getTrialDays() : 0)
                .maxUsers(dto.getMaxUsers())
                .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                .build();

        plan = planRepository.save(plan);
        log.info("Created plan: {}", plan.getCode());
        return BillingMapper.toPlanDto(plan);
    }

    @Transactional
    public PlanDto updatePlan(Long id, PlanCreateDto dto) {
        Plan plan = loadPlanById(id);

        plan.setCode(dto.getCode());
        plan.setName(dto.getName());
        plan.setDescription(dto.getDescription());
        plan.setMonthlyPrice(dto.getMonthlyPrice());
        plan.setAnnualPrice(dto.getAnnualPrice());
        if (dto.getCurrency() != null) plan.setCurrency(dto.getCurrency());
        if (dto.getIsPublic() != null) plan.setIsPublic(dto.getIsPublic());
        if (dto.getTrialDays() != null) plan.setTrialDays(dto.getTrialDays());
        plan.setMaxUsers(dto.getMaxUsers());
        if (dto.getSortOrder() != null) plan.setSortOrder(dto.getSortOrder());

        plan = planRepository.save(plan);
        subscriptionCache.evictAllOnWrite();
        log.info("Updated plan: {}", plan.getCode());
        return BillingMapper.toPlanDto(plan);
    }

    @Transactional
    public void deactivatePlan(Long id) {
        Plan plan = loadPlanById(id);
        plan.setIsActive(false);
        planRepository.save(plan);
        subscriptionCache.evictAllOnWrite();
        log.info("Deactivated plan: {}", plan.getCode());
    }

    @Transactional
    public void activatePlan(Long id) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> new PlanNotFoundException("Plan with ID " + id + " was not found"));
        plan.setIsActive(true);
        planRepository.save(plan);
        subscriptionCache.evictAllOnWrite();
        log.info("Activated plan: {}", plan.getCode());
    }

    @Transactional
    public PlanDto assignFeatureToPlan(Long planId, PlanFeatureAssignDto dto) {
        Plan plan = loadPlanById(planId);

        Feature feature = featureRepository.findByCodeAndIsActiveTrue(dto.getFeatureCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Active feature with code '" + dto.getFeatureCode() + "' was not found"));

        boolean alreadyAssigned = plan.getPlanFeatures().stream()
                .anyMatch(pf -> pf.getFeature().getCode().equals(dto.getFeatureCode()));

        if (alreadyAssigned) {
            throw new ValidationException(
                    "Feature '" + dto.getFeatureCode() + "' is already assigned to plan '" + plan.getCode() + "'");
        }

        PlanFeature planFeature = PlanFeature.builder()
                .plan(plan)
                .feature(feature)
                .enabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                .quotaLimit(dto.getQuotaLimit())
                .quotaPeriod(dto.getQuotaPeriod())
                .build();

        plan.addFeature(planFeature);
        plan = planRepository.save(plan);
        subscriptionCache.evictAllOnWrite();

        log.info("Assigned feature {} to plan {}", dto.getFeatureCode(), plan.getCode());
        return BillingMapper.toPlanDto(plan);
    }

    @Transactional
    public PlanDto removeFeatureFromPlan(Long planId, String featureCode) {
        Plan plan = loadPlanById(planId);

        Plan finalPlan = plan;
        PlanFeature planFeature = plan.getPlanFeatures().stream()
                .filter(pf -> pf.getFeature().getCode().equals(featureCode))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Feature '" + featureCode + "' is not assigned to plan '" + finalPlan.getCode() + "'"));

        plan.removeFeature(planFeature);
        plan = planRepository.save(plan);
        subscriptionCache.evictAllOnWrite();

        log.info("Removed feature {} from plan {}", featureCode, plan.getCode());
        return BillingMapper.toPlanDto(plan);
    }
}

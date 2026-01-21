package org.tornotron.echno_backend.billing.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.Feature;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.PlanFeature;
import org.tornotron.echno_backend.billing.dto.PlanCreateDto;
import org.tornotron.echno_backend.billing.dto.PlanFeatureAssignDto;
import org.tornotron.echno_backend.billing.repositories.FeatureRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.common.exception.PlanNotFoundException;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class PlanService {

    private final PlanRepository planRepository;
    private final FeatureRepository featureRepository;

    public List<Plan> getAllPublicPlans() {
        return planRepository.findPublicPlansWithFeatures();
    }

    public List<Plan> getAllPlans() {
        return planRepository.findAllWithFeatures();
    }

    public Plan getPlanById(Long id) {
        return planRepository.findByIdWithFeatures(id)
                .orElseThrow(() -> new PlanNotFoundException("Plan not found with id: " + id));
    }

    public Plan getPlanByCode(String code) {
        return planRepository.findByCodeWithFeatures(code)
                .orElseThrow(() -> new PlanNotFoundException("Plan not found with code: " + code));
    }

    @Transactional
    public Plan createPlan(PlanCreateDto dto) {
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
        return plan;
    }

    @Transactional
    public Plan updatePlan(Long id, PlanCreateDto dto) {
        Plan plan = getPlanById(id);

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
        log.info("Updated plan: {}", plan.getCode());
        return plan;
    }

    @Transactional
    public void deactivatePlan(Long id) {
        Plan plan = getPlanById(id);
        plan.setIsActive(false);
        planRepository.save(plan);
        log.info("Deactivated plan: {}", plan.getCode());
    }

    @Transactional
    public void activatePlan(Long id) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> new PlanNotFoundException("Plan not found with id: " + id));
        plan.setIsActive(true);
        planRepository.save(plan);
        log.info("Activated plan: {}", plan.getCode());
    }

    @Transactional
    public Plan assignFeatureToPlan(Long planId, PlanFeatureAssignDto dto) {
        Plan plan = getPlanById(planId);

        Feature feature = featureRepository.findByCodeAndIsActiveTrue(dto.getFeatureCode())
                .orElseThrow(() -> new IllegalArgumentException("Feature not found: " + dto.getFeatureCode()));

        boolean alreadyAssigned = plan.getPlanFeatures().stream()
                .anyMatch(pf -> pf.getFeature().getCode().equals(dto.getFeatureCode()));

        if (alreadyAssigned) {
            throw new IllegalArgumentException("Feature already assigned to this plan: " + dto.getFeatureCode());
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

        log.info("Assigned feature {} to plan {}", dto.getFeatureCode(), plan.getCode());
        return plan;
    }

    @Transactional
    public Plan removeFeatureFromPlan(Long planId, String featureCode) {
        Plan plan = getPlanById(planId);

        PlanFeature planFeature = plan.getPlanFeatures().stream()
                .filter(pf -> pf.getFeature().getCode().equals(featureCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Feature not assigned to plan: " + featureCode));

        plan.removeFeature(planFeature);
        plan = planRepository.save(plan);

        log.info("Removed feature {} from plan {}", featureCode, plan.getCode());
        return plan;
    }
}

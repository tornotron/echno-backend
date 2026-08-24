package org.tornotron.echno_backend.finance.budget.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.budget.domain.BudgetAllocation;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;
import org.tornotron.echno_backend.finance.budget.dtos.BudgetAllocationDto;
import org.tornotron.echno_backend.finance.budget.dtos.UpsertBudgetAllocationRequest;
import org.tornotron.echno_backend.finance.budget.mapper.BudgetAllocationMapper;
import org.tornotron.echno_backend.finance.budget.repositories.BudgetAllocationRepository;
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.util.List;
import java.util.UUID;

/**
 * Manages a project's budget: the set of {@link BudgetAllocation} rows, one per budget head.
 *
 * <p>The upsert is keyed on (project, cost category), which the unique constraint enforces, so setting
 * the amount for a head that already has an allocation replaces it rather than adding a second row.
 * The project and cost category are both validated against the current tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetAllocationService {

    private final BudgetAllocationRepository repo;
    private final CostCategoryRepository categoryRepo;
    private final ProjectRepository projectRepository;
    private final BudgetAllocationMapper mapper;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional(readOnly = true)
    public List<BudgetAllocationDto> findByProject(Long projectId) {
        requireProject(projectId);
        return mapper.toDtos(
                repo.findByProject_IdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId()));
    }

    /**
     * Sets the amount allocated to a budget head on a project, creating the allocation on first use
     * and replacing the amount thereafter.
     */
    @Transactional
    public BudgetAllocationDto upsert(Long projectId, UUID costCategoryId, UpsertBudgetAllocationRequest req) {
        Project project = requireProject(projectId);
        CostCategory category = requireCategory(costCategoryId);

        BudgetAllocation allocation = repo.findByProjectAndCategory(projectId, costCategoryId)
                .orElseGet(() -> {
                    BudgetAllocation created = new BudgetAllocation();
                    created.setProject(project);
                    created.setCostCategory(category);
                    created.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
                    return created;
                });
        allocation.setAllocatedAmount(MoneyUtils.normalize(req.allocatedAmount()));

        BudgetAllocation saved = repo.save(allocation);
        log.info("Upserted budget allocation for project {} category {} at {}",
                projectId, costCategoryId, saved.getAllocatedAmount());
        return mapper.toDto(saved);
    }

    @Transactional
    public void delete(Long projectId, UUID costCategoryId) {
        requireProject(projectId);
        BudgetAllocation allocation = repo.findByProjectAndCategory(projectId, costCategoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No budget allocation for project " + projectId + " and cost category "
                                + costCategoryId));
        repo.delete(allocation);
        log.info("Deleted budget allocation for project {} category {}", projectId, costCategoryId);
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project with ID " + projectId + " was not found"));
    }

    private CostCategory requireCategory(UUID costCategoryId) {
        return categoryRepo.findScopedById(costCategoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cost category with ID " + costCategoryId + " was not found"));
    }
}

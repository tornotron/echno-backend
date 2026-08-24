package org.tornotron.echno_backend.finance.budget.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.configuration.MoneyUtils;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.finance.budget.domain.BudgetAllocation;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;
import org.tornotron.echno_backend.finance.budget.dtos.ProjectCostControlDto;
import org.tornotron.echno_backend.finance.budget.dtos.ProjectCostControlLineDto;
import org.tornotron.echno_backend.finance.budget.repositories.BudgetAllocationRepository;
import org.tornotron.echno_backend.finance.budget.repositories.CategoryCostAggregate;
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceLineRepository;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the project cost-control view: for each budget head on a project, the amount allocated
 * against what has been committed (approved but not fully paid) and spent (fully paid), and what
 * remains.
 *
 * <p>The heads shown are the union of those with a budget allocation and those with any tagged spend,
 * so a category that has cost against it but no allocation still surfaces (as over budget). The
 * committed and spent amounts come from one grouped query over the tagged invoice lines, never a
 * per-invoice fan-out. A project total row sums each column.
 */
@Service
@RequiredArgsConstructor
public class ProjectCostControlService {

    private static final String TOTAL_LABEL = "Total";

    // Approved and beyond, but not a terminal fully-paid or cancelled state: these are the
    // statuses whose unpaid balance counts as a committed obligation.
    private static final List<ConstructionInvoiceStatus> COMMITTED_STATUSES = List.of(
            ConstructionInvoiceStatus.APPROVED,
            ConstructionInvoiceStatus.SENT,
            ConstructionInvoiceStatus.PARTIALLY_PAID,
            ConstructionInvoiceStatus.OVERDUE);

    private final BudgetAllocationRepository allocationRepo;
    private final CostCategoryRepository categoryRepo;
    private final ConstructionInvoiceLineRepository lineRepo;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public ProjectCostControlDto getForProject(Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (!projectRepository.existsByIdAndOrganization_Id(projectId, orgId)) {
            throw new ResourceNotFoundException("Project with ID " + projectId + " was not found");
        }

        Map<UUID, BigDecimal> allocatedByCategory = new HashMap<>();
        for (BudgetAllocation allocation : allocationRepo.findByProject_IdAndOrganization_Id(projectId, orgId)) {
            allocatedByCategory.put(allocation.getCostCategory().getId(),
                    MoneyUtils.normalize(allocation.getAllocatedAmount()));
        }

        Map<UUID, BigDecimal> committedByCategory = new HashMap<>();
        Map<UUID, BigDecimal> spentByCategory = new HashMap<>();
        List<CategoryCostAggregate> aggregates = lineRepo.aggregateByCategoryForProject(
                projectId, orgId, ConstructionPaymentStatus.PAID, COMMITTED_STATUSES);
        for (CategoryCostAggregate row : aggregates) {
            committedByCategory.put(row.getCostCategoryId(), MoneyUtils.normalize(row.getCommitted()));
            spentByCategory.put(row.getCostCategoryId(), MoneyUtils.normalize(row.getSpent()));
        }

        // Union of heads that carry an allocation or any tagged spend, in a stable order.
        Set<UUID> categoryIds = new LinkedHashSet<>();
        categoryIds.addAll(allocatedByCategory.keySet());
        categoryIds.addAll(committedByCategory.keySet());

        Map<UUID, String> nameById = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            for (CostCategory category : categoryRepo.findByIdIn(categoryIds)) {
                nameById.put(category.getId(), category.getName());
            }
        }

        List<ProjectCostControlLineDto> lines = new ArrayList<>();
        BigDecimal totalAllocated = BigDecimal.ZERO;
        BigDecimal totalCommitted = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;

        for (UUID categoryId : categoryIds) {
            BigDecimal allocated = allocatedByCategory.getOrDefault(categoryId, MoneyUtils.normalize(null));
            BigDecimal committed = committedByCategory.getOrDefault(categoryId, MoneyUtils.normalize(null));
            BigDecimal spent = spentByCategory.getOrDefault(categoryId, MoneyUtils.normalize(null));
            lines.add(buildLine(categoryId, nameById.getOrDefault(categoryId, "(unknown)"),
                    allocated, committed, spent));

            totalAllocated = totalAllocated.add(allocated);
            totalCommitted = totalCommitted.add(committed);
            totalSpent = totalSpent.add(spent);
        }

        // Present heads alphabetically by name for a stable, readable view.
        lines.sort(Comparator.comparing(ProjectCostControlLineDto::costCategoryName,
                String.CASE_INSENSITIVE_ORDER));

        ProjectCostControlLineDto totals = buildLine(null, TOTAL_LABEL,
                MoneyUtils.normalize(totalAllocated),
                MoneyUtils.normalize(totalCommitted),
                MoneyUtils.normalize(totalSpent));

        return new ProjectCostControlDto(projectId, lines, totals);
    }

    private ProjectCostControlLineDto buildLine(UUID categoryId, String name,
                                                BigDecimal allocated, BigDecimal committed, BigDecimal spent) {
        BigDecimal consumed = committed.add(spent);
        BigDecimal remaining = MoneyUtils.normalize(allocated.subtract(consumed));
        boolean overBudget = consumed.compareTo(allocated) > 0;
        return new ProjectCostControlLineDto(categoryId, name,
                MoneyUtils.normalize(allocated), MoneyUtils.normalize(committed),
                MoneyUtils.normalize(spent), remaining, overBudget);
    }
}

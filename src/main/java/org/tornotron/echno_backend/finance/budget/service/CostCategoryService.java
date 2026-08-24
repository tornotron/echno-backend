package org.tornotron.echno_backend.finance.budget.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;
import org.tornotron.echno_backend.finance.budget.dtos.CostCategoryDto;
import org.tornotron.echno_backend.finance.budget.dtos.CreateCostCategoryRequest;
import org.tornotron.echno_backend.finance.budget.dtos.UpdateCostCategoryRequest;
import org.tornotron.echno_backend.finance.budget.mapper.CostCategoryMapper;
import org.tornotron.echno_backend.finance.budget.repositories.CostCategoryRepository;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.repositories.AccountRepository;

import java.util.List;
import java.util.UUID;

/**
 * Manages the org-level list of budget heads (cost categories): lookups, creation, editing, and
 * deactivation. A head may be mapped to a ledger expense account so budget reporting and the chart of
 * accounts line up. Names are unique per organization, and a head is deactivated rather than deleted
 * so invoice lines tagged to it keep their reference.
 */
@Service
@RequiredArgsConstructor
public class CostCategoryService {

    private final CostCategoryRepository repo;
    private final CostCategoryMapper mapper;
    private final AccountRepository accountRepo;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional(readOnly = true)
    public List<CostCategoryDto> findAll(boolean activeOnly) {
        return mapper.toDtos(activeOnly
                ? repo.findByActiveTrue()
                : repo.findAll(Sort.by("name")));
    }

    @Transactional(readOnly = true)
    public CostCategoryDto findById(UUID id) {
        return mapper.toDto(requireCategory(id));
    }

    @Transactional
    public CostCategoryDto create(CreateCostCategoryRequest req) {
        String name = req.name().trim();
        if (repo.existsByName(name)) {
            throw new InvalidRequestException(
                    "Cost category '" + name + "' already exists in this organization");
        }
        CostCategory category = new CostCategory();
        category.setName(name);
        category.setCode(trimToNull(req.code()));
        category.setExpenseAccount(resolveAccount(req.expenseAccountId()));
        category.setActive(true);
        category.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        return mapper.toDto(repo.save(category));
    }

    @Transactional
    public CostCategoryDto update(UUID id, UpdateCostCategoryRequest req) {
        CostCategory category = requireCategory(id);
        String name = req.name().trim();
        if (repo.existsByNameAndIdNot(name, id)) {
            throw new InvalidRequestException(
                    "Cost category '" + name + "' already exists in this organization");
        }
        category.setName(name);
        category.setCode(trimToNull(req.code()));
        category.setExpenseAccount(resolveAccount(req.expenseAccountId()));
        category.setActive(req.active());
        return mapper.toDto(category);
    }

    @Transactional
    public CostCategoryDto deactivate(UUID id) {
        CostCategory category = requireCategory(id);
        category.setActive(false);
        return mapper.toDto(category);
    }

    private CostCategory requireCategory(UUID id) {
        return repo.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Cost category with ID " + id + " was not found"));
    }

    /**
     * Resolves an expense account by id within the current tenant, or null when no id is given.
     * Guards that a mapped account belongs to the tenant.
     */
    private Account resolveAccount(UUID accountId) {
        if (accountId == null) {
            return null;
        }
        return accountRepo.findScopedById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account with ID " + accountId + " was not found"));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

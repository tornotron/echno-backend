package org.tornotron.echno_backend.finance.budget.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.finance.budget.domain.BudgetAllocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetAllocationRepository extends JpaRepository<BudgetAllocation, UUID> {

    List<BudgetAllocation> findByProject_IdAndOrganization_Id(Long projectId, Long organizationId);

    /**
     * Org-scoped lookup of the single allocation for a project and cost-category pair, which the
     * unique constraint guarantees. Used by the upsert and delete paths.
     */
    @Query("SELECT a FROM BudgetAllocation a WHERE a.project.id = :projectId "
            + "AND a.costCategory.id = :costCategoryId")
    Optional<BudgetAllocation> findByProjectAndCategory(@Param("projectId") Long projectId,
                                                        @Param("costCategoryId") UUID costCategoryId);
}

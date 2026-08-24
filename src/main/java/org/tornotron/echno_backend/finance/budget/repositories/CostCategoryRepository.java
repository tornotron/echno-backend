package org.tornotron.echno_backend.finance.budget.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CostCategoryRepository extends JpaRepository<CostCategory, UUID> {

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads.
     */
    @Query("SELECT c FROM CostCategory c WHERE c.id = :id")
    Optional<CostCategory> findScopedById(@Param("id") UUID id);

    List<CostCategory> findByActiveTrue();

    /** All active categories whose id is in the given set, scoped to the current tenant. */
    List<CostCategory> findByIdIn(Collection<UUID> ids);

    /**
     * Whether another category in the current tenant already uses the given name, excluding the
     * category with the given id. Used on edit so a category can keep its own name while a clash
     * with any other is rejected.
     */
    boolean existsByNameAndIdNot(String name, UUID id);

    boolean existsByName(String name);

    /**
     * Whether the given organization already owns any cost category. Used by the seeder to stay
     * idempotent. Queries the organization id directly so it does not depend on the Hibernate
     * {@code orgFilter} being enabled.
     */
    boolean existsByOrganizationId(Long organizationId);
}

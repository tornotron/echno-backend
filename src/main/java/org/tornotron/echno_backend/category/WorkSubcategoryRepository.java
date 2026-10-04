package org.tornotron.echno_backend.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Repository for {@link WorkSubcategory}. Every read names the organization. */
public interface WorkSubcategoryRepository extends JpaRepository<WorkSubcategory, Long> {

    /**
     * The sub-categories of one category, in dropdown order. Bounded by what one category holds:
     * the standard list has at most a dozen per category.
     */
    List<WorkSubcategory> findByCategory_IdAndOrganization_IdOrderBySortOrderAscIdAsc(Long categoryId, Long organizationId);

    boolean existsByCategory_IdAndNormalizedName(Long categoryId, String normalizedName);
}

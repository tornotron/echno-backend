package org.tornotron.echno_backend.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


/**
 * Repository interface for {@link Category} entities.
 * Provides methods to perform database operations on categories.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {
    /**
     * Finds a category by its name.
     *
     * @param name The name of the category to find. Must not be blank and must be between 3 and 50 characters.
     * @return An {@link Optional} containing the found {@link Category}, or {@link Optional#empty()} if no category with the given name exists.
     */
    Optional<Category> findCategoryByName(@NotBlank(message = "name is required") @Size(min = 3,max = 50, message = "name must be between 3 and 50 characters") String name);

    Optional<Category> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Whether this organization already holds a category whose name normalizes to the given
     * string. Scoped to the organization because the constraint behind it,
     * {@code uk_category_org_normalized_name}, is: two tenants may both have a "Civil Works".
     */
    boolean existsByNormalizedNameAndOrganization_Id(String normalizedName, Long organizationId);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);
}
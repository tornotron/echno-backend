package org.tornotron.echno_backend.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findCategoryByName(@NotBlank(message = "name is required") @Size(min = 3,max = 50, message = "name must be between 3 and 50 characters") String name);
}

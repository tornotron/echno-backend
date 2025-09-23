package org.tornotron.echno_backend.category;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.CategoryDtoConvertor;
import org.tornotron.echno_backend.category.dto.CategoryCreationDto;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;


/**
 * Service class for managing categories.
 * Handles business logic related to category creation, retrieval, and deletion.
 */
@Service
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;

    /**
     * Constructs a CategoryService with the given CategoryRepository.
     *
     * @param categoryRepository The repository for category data access.
     */
    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Creates a new category.
     *
     * @param categoryCreationDto DTO containing the details for the new category.
     * @throws DatabaseOperationException if the category cannot be saved.
     */
    public void addCategory(CategoryCreationDto categoryCreationDto) {
        Category category = new Category();
        category.setName(categoryCreationDto.getName());
        category.setDescription(categoryCreationDto.getDescription());
        category.setIcon(categoryCreationDto.getIcon());
        category.setImage(categoryCreationDto.getImage());
        Category savedCategory = categoryRepository.save(category);
        if(savedCategory.getId() == null) {
            throw new DatabaseOperationException("Category could not be created");
        }
    }

    /**
     * Retrieves a paginated list of all categories.
     *
     * @param pageNo   The page number to retrieve.
     * @param pageSize The number of categories per page.
     * @return A {@link Page} of category DTOs.
     */
    public Page<CategoryDto> getAllCategories(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return categoryRepository.findAll(pageable)
                .map(CategoryDtoConvertor::convertCategoryToDto);
    }

    /**
     * Retrieves a single category by its ID.
     *
     * @param id The ID of the category to retrieve.
     * @return The category DTO.
     * @throws ResourceNotFoundException if no category with the given ID is found.
     */
    @Transactional(readOnly = true)
    public CategoryDto getACategory(Long id) {
        CategoryDto categoryDto = categoryRepository.findById(id)
                .map(CategoryDtoConvertor::convertCategoryToDto)
                .orElse(null);

        if(categoryDto == null) {
            throw new ResourceNotFoundException("Category not found with id: " + id);
        } else {
            return categoryDto;
        }
    }

    /**
     * Deletes a category by its ID.
     *
     * @param id The ID of the category to delete.
     * @throws ResourceNotFoundException if no category with the given ID is found.
     */
    public void deleteACategory(Long id) {
        if(!categoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("Category not found with id: " + id);
        }
        categoryRepository.deleteById(id);
    }
}